package io.agent.helm.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agent.helm.core.error.ContextOverflowException;
import io.agent.helm.core.error.ErrorCode;
import io.agent.helm.core.error.HelmException;
import io.agent.helm.core.error.ProviderException;
import io.agent.helm.core.message.ContentBlock;
import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.message.Role;
import io.agent.helm.core.message.TextBlock;
import io.agent.helm.core.message.ToolCallBlock;
import io.agent.helm.core.message.ToolResultBlock;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.tool.ToolDescriptor;
import io.agent.helm.core.type.JsonSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link ModelProvider} for the Anthropic Messages API. Event-typed SSE ({@code message_start},
 * {@code content_block_delta}, {@code message_stop}, ...) is normalized to Helm's {@link ModelStreamEvent} model.
 * {@code tool_use} content blocks and their {@code input_json_delta} fragments are aggregated before a
 * {@link ModelStreamEvent.ToolCallRequested} is emitted.
 *
 * <p><b>Base URL convention.</b> {@code baseUrl} includes the API version path segment (e.g.
 * {@code https://api.anthropic.com/v1}); request paths are appended without re-adding {@code /v1}. A trailing {@code /}
 * is stripped on build. This mirrors the {@code OpenAiProvider} convention.
 *
 * <p><b>Retry policy.</b> This provider does not retry 429/5xx responses. Callers are responsible for backoff and retry
 * (e.g. via a wrapping {@code ModelProvider}); the {@code Retry-After} header is surfaced in error details when
 * present.
 */
public final class AnthropicProvider implements ModelProvider {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String providerId;
    private final String baseUrl;
    private final String apiKey;
    private final int maxTokens;
    private final Duration defaultTimeout;
    private final HttpClient httpClient;

    private AnthropicProvider(
            String providerId,
            String baseUrl,
            String apiKey,
            int maxTokens,
            Duration defaultTimeout,
            HttpClient httpClient) {
        this.providerId = providerId;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.defaultTimeout = defaultTimeout;
        this.httpClient = httpClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean supports(ModelRef model) {
        return providerId.equals(model.providerId());
    }

    @Override
    public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
        // Defer starting the producer until a subscriber is registered, so no items are submitted
        // (and dropped) before onSubscribe. The subscriber is wrapped so subscriber cancellation
        // is observable from the streaming virtual thread.
        return subscriber -> {
            SubmissionPublisher<ModelStreamEvent> publisher = new SubmissionPublisher<>();
            AtomicBoolean cancelled = new AtomicBoolean(false);
            publisher.subscribe(new CancellationTrackingSubscriber(subscriber, cancelled));
            Thread.startVirtualThread(() -> streamAsync(request, publisher, cancelled));
        };
    }

    private void streamAsync(
            ModelRequest request, SubmissionPublisher<ModelStreamEvent> publisher, AtomicBoolean cancelled) {
        try {
            HttpRequest httpRequest = buildRequest(request);
            HttpResponse<InputStream> response = httpClient.send(httpRequest, BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status >= 400) {
                    publisher.closeExceptionally(mapHttpError(status, response.headers(), readAll(body)));
                    return;
                }
                if (!isEventStream(response.headers())) {
                    publisher.closeExceptionally(new ProviderException(
                            ErrorCode.PROVIDER_ERROR,
                            "anthropic api returned a non-streaming response",
                            Map.of("provider", providerId, "status", status),
                            Map.of("contentType", contentType(response.headers()))));
                    return;
                }
                parseSse(body, publisher, cancelled);
            }
        } catch (HttpTimeoutException e) {
            publisher.closeExceptionally(new ProviderException(
                    ErrorCode.PROVIDER_TIMEOUT, "request timed out", Map.of("provider", providerId), Map.of(), e));
        } catch (IOException e) {
            publisher.closeExceptionally(new ProviderException(
                    ErrorCode.PROVIDER_ERROR,
                    "anthropic request failed",
                    Map.of("provider", providerId),
                    Map.of("message", String.valueOf(e.getMessage())),
                    e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            publisher.closeExceptionally(new ProviderException(
                    ErrorCode.PROVIDER_ERROR, "request interrupted", Map.of("provider", providerId), Map.of(), e));
        } catch (RuntimeException e) {
            publisher.closeExceptionally(
                    e instanceof HelmException he
                            ? he
                            : new ProviderException(
                                    ErrorCode.PROVIDER_ERROR,
                                    "unexpected error",
                                    Map.of("provider", providerId),
                                    Map.of("message", String.valueOf(e.getMessage())),
                                    e));
        }
    }

    private void parseSse(InputStream body, SubmissionPublisher<ModelStreamEvent> publisher, AtomicBoolean cancelled)
            throws IOException {
        Map<Integer, ToolCallAccumulator> tools = new LinkedHashMap<>();
        long inputTokens = 0;
        long outputTokens = 0;
        long cacheReadTokens = 0;
        long cacheWriteTokens = 0;
        long reasoningTokens = 0;
        String finishReason = null;
        boolean receivedAnyEvent = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Stop reading once the subscriber has cancelled so the HTTP body is released promptly.
                if (cancelled.get() || !publisher.hasSubscribers()) {
                    return;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.isEmpty()) {
                    continue;
                }
                receivedAnyEvent = true;
                JsonNode node;
                try {
                    node = OBJECT_MAPPER.readTree(data);
                } catch (JsonProcessingException e) {
                    publisher.closeExceptionally(new ProviderException(
                            ErrorCode.PROVIDER_ERROR,
                            "malformed SSE event from anthropic",
                            Map.of("provider", providerId),
                            Map.of("line", data, "message", String.valueOf(e.getMessage())),
                            e));
                    return;
                }
                String type = node.path("type").asText("");
                switch (type) {
                    case "error" -> {
                        JsonNode errorNode = node.path("error");
                        String message = errorNode.path("message").asText("anthropic stream error");
                        publisher.closeExceptionally(new ProviderException(
                                ErrorCode.PROVIDER_ERROR,
                                message,
                                Map.of("provider", providerId),
                                Map.of("error", errorNode.toString())));
                        return;
                    }
                    case "message_start" -> {
                        JsonNode usage = node.path("message").path("usage");
                        inputTokens = usage.path("input_tokens").asLong(0);
                        cacheReadTokens = usage.path("cache_read_input_tokens").asLong(0);
                        cacheWriteTokens =
                                usage.path("cache_creation_input_tokens").asLong(0);
                    }
                    case "content_block_start" -> {
                        JsonNode block = node.path("content_block");
                        if ("tool_use".equals(block.path("type").asText())) {
                            int index = node.path("index").asInt(0);
                            ToolCallAccumulator acc = new ToolCallAccumulator();
                            acc.id = block.path("id").asText("");
                            acc.name = block.path("name").asText("");
                            tools.put(index, acc);
                        }
                    }
                    case "content_block_delta" -> {
                        int index = node.path("index").asInt(0);
                        JsonNode delta = node.path("delta");
                        String deltaType = delta.path("type").asText("");
                        if ("text_delta".equals(deltaType)) {
                            publisher.submit(new ModelStreamEvent.ContentDelta(
                                    delta.path("text").asText("")));
                        } else if ("input_json_delta".equals(deltaType)) {
                            ToolCallAccumulator acc = tools.get(index);
                            if (acc != null) {
                                acc.arguments.append(delta.path("partial_json").asText(""));
                            }
                        }
                    }
                    case "content_block_stop" -> {
                        int index = node.path("index").asInt(0);
                        ToolCallAccumulator acc = tools.remove(index);
                        if (acc != null) {
                            publisher.submit(new ModelStreamEvent.ToolCallRequested(
                                    acc.id, acc.name, parseArguments(acc.arguments.toString())));
                        }
                    }
                    case "message_delta" -> {
                        outputTokens = node.path("usage").path("output_tokens").asLong(outputTokens);
                        String stopReason =
                                node.path("delta").path("stop_reason").asText(null);
                        if (stopReason != null && !stopReason.isEmpty() && !"null".equals(stopReason)) {
                            finishReason = stopReason;
                        }
                    }
                    case "message_stop" -> {
                        // Terminal event; completion is emitted after the loop.
                    }
                    default -> {
                        // Ignore unknown event types.
                    }
                }
            }
        }
        if (!receivedAnyEvent) {
            publisher.closeExceptionally(new ProviderException(
                    ErrorCode.PROVIDER_ERROR,
                    "anthropic stream ended without any events",
                    Map.of("provider", providerId),
                    Map.of()));
            return;
        }
        for (ToolCallAccumulator acc : tools.values()) {
            publisher.submit(
                    new ModelStreamEvent.ToolCallRequested(acc.id, acc.name, parseArguments(acc.arguments.toString())));
        }
        publisher.submit(new ModelStreamEvent.Completed(
                new TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, reasoningTokens),
                finishReason));
        publisher.close();
    }

    private HttpRequest buildRequest(ModelRequest request) throws IOException {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", request.model().modelId());
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        StringBuilder system = new StringBuilder(request.instructions() == null ? "" : request.instructions());
        body.set("messages", buildMessages(request.messages(), system));
        if (!system.isEmpty()) {
            body.put("system", system.toString());
        }
        if (!request.tools().isEmpty()) {
            body.set("tools", buildTools(request.tools()));
        }
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/messages"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                .build();
    }

    private ArrayNode buildMessages(List<HelmMessage> messages, StringBuilder system) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        for (HelmMessage message : messages) {
            if (message.role() == Role.SYSTEM) {
                for (ContentBlock block : message.content()) {
                    if (block instanceof TextBlock t) {
                        system.append(t.text()).append('\n');
                    }
                }
                continue;
            }
            array.add(toMessageNode(message));
        }
        return array;
    }

    private ObjectNode toMessageNode(HelmMessage message) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("role", roleName(message.role()));
        ArrayNode content = node.putArray("content");
        for (ContentBlock block : message.content()) {
            switch (block) {
                case TextBlock t -> content.addObject().put("type", "text").put("text", t.text());
                case ToolCallBlock tc -> {
                    ObjectNode use = content.addObject();
                    use.put("type", "tool_use");
                    use.put("id", tc.id());
                    use.put("name", tc.name());
                    use.set(
                            "input",
                            tc.input() == null
                                    ? OBJECT_MAPPER.createObjectNode()
                                    : OBJECT_MAPPER.valueToTree(tc.input()));
                }
                case ToolResultBlock tr -> {
                    ObjectNode result = content.addObject();
                    result.put("type", "tool_result");
                    result.put("tool_use_id", tr.toolCallId());
                    result.put("content", serializeToolOutput(tr.output()));
                    result.put("is_error", tr.error());
                }
            }
        }
        return node;
    }

    private ArrayNode buildTools(List<ToolDescriptor> tools) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        for (ToolDescriptor tool : tools) {
            ObjectNode entry = array.addObject();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.set("input_schema", toJsonNode(tool.inputSchema()));
        }
        return array;
    }

    private JsonNode toJsonNode(JsonSchema schema) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("type", schema.type());
        if (schema.description() != null && !schema.description().isBlank()) {
            node.put("description", schema.description());
        }
        if (!schema.properties().isEmpty()) {
            ObjectNode props = node.putObject("properties");
            schema.properties().forEach((key, value) -> props.set(key, toJsonNode(value)));
        }
        if (!schema.required().isEmpty()) {
            ArrayNode required = node.putArray("required");
            schema.required().forEach(required::add);
        }
        if (schema.items() != null) {
            node.set("items", toJsonNode(schema.items()));
        }
        if (schema.enumValues() != null && !schema.enumValues().isEmpty()) {
            ArrayNode enumArray = node.putArray("enum");
            schema.enumValues().forEach(enumArray::add);
        }
        if (schema.additionalProperties() != null) {
            node.set("additionalProperties", toJsonNode(schema.additionalProperties()));
        }
        if (schema.nullable()) {
            // Anthropic JSON Schema supports the standard "type": ["string","null"] form for nullable types.
            ArrayNode types = node.putArray("type");
            types.add(schema.type());
            types.add("null");
            node.remove("type");
            node.set("type", types);
        }
        return node;
    }

    private HelmException mapHttpError(int status, HttpHeaders headers, String body) {
        if (status == 400 && body != null && body.contains("prompt_too_long")) {
            return ContextOverflowException.prompt(
                    "Anthropic prompt too long", Map.of("provider", providerId, "status", status));
        }
        ErrorCode code = ErrorCode.PROVIDER_ERROR;
        if (status == 429) {
            code = ErrorCode.PROVIDER_RATE_LIMITED;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", providerId);
        details.put("status", status);
        long retryAfterSeconds = parseRetryAfter(headers);
        if (retryAfterSeconds >= 0) {
            details.put("retryAfterSeconds", retryAfterSeconds);
        }
        return new ProviderException(code, "anthropic api error", details, Map.of("body", body));
    }

    private static Object parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(arguments, Object.class);
        } catch (Exception e) {
            // Preserve the raw payload so the API roundtrip stays valid; callers can still dispatch the tool call.
            return Map.of("_raw", arguments);
        }
    }

    private static String serializeToolOutput(Object toolOutput) {
        if (toolOutput == null) {
            return "";
        }
        // String outputs are used verbatim; JSON-serializing them would add surrounding quotes.
        if (toolOutput instanceof String s) {
            return s;
        }
        return writeJson(toolOutput);
    }

    private static boolean isEventStream(HttpHeaders headers) {
        return contentType(headers).contains("text/event-stream");
    }

    private static String contentType(HttpHeaders headers) {
        return headers.firstValue("Content-Type").orElse("");
    }

    private static long parseRetryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim());
                    } catch (NumberFormatException ignored) {
                        return -1L;
                    }
                })
                .orElse(-1L);
    }

    private static String roleName(Role role) {
        return switch (role) {
            case USER, TOOL -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
        };
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            return String.valueOf(value);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Wraps a subscriber so that {@link Flow.Subscription#cancel()} flips a flag observable from the streaming virtual
     * thread, allowing the SSE reader loop to stop and release the HTTP body promptly.
     */
    private static final class CancellationTrackingSubscriber implements Flow.Subscriber<ModelStreamEvent> {
        private final Flow.Subscriber<? super ModelStreamEvent> downstream;
        private final AtomicBoolean cancelled;

        CancellationTrackingSubscriber(Flow.Subscriber<? super ModelStreamEvent> downstream, AtomicBoolean cancelled) {
            this.downstream = downstream;
            this.cancelled = cancelled;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            downstream.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscription.request(n);
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                    subscription.cancel();
                }
            });
        }

        @Override
        public void onNext(ModelStreamEvent item) {
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }
    }

    private static final class ToolCallAccumulator {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }

    public static final class Builder {
        private String providerId = "anthropic";
        private String baseUrl = "https://api.anthropic.com/v1";
        private String apiKey;
        private int maxTokens = 1024;
        private Duration defaultTimeout = Duration.ofSeconds(30);
        private HttpClient httpClient = HttpClient.newHttpClient();

        public Builder providerId(String providerId) {
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            return this;
        }

        /**
         * Sets the API base URL <b>including the {@code /v1} segment</b> (e.g. {@code https://api.anthropic.com/v1}).
         * Request paths ({@code /messages}) are appended without re-adding {@code /v1}. A trailing {@code /} is
         * stripped. This matches the {@code OpenAiProvider} convention.
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        public AnthropicProvider build() {
            if (apiKey == null) {
                throw new IllegalArgumentException("apiKey is required");
            }
            String normalized = baseUrl;
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return new AnthropicProvider(providerId, normalized, apiKey, maxTokens, defaultTimeout, httpClient);
        }
    }
}
