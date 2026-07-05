package io.agent.helm.provider.openai;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link ModelProvider} for OpenAI-compatible Chat Completions endpoints (configurable base URL, so vLLM, Ollama, and
 * other compatible servers work). Streaming responses (SSE) are normalized to Helm's {@link ModelStreamEvent} model;
 * tool-call argument fragments are aggregated before a {@link ModelStreamEvent.ToolCallRequested} is emitted.
 *
 * <p><b>Base URL convention.</b> {@code baseUrl} includes the API version path segment (e.g.
 * {@code https://api.openai.com/v1}); request paths are appended without re-adding {@code /v1}. A trailing {@code /} is
 * stripped on build.
 *
 * <p><b>Retry policy.</b> This provider does not retry 429/5xx responses. Callers are responsible for backoff and retry
 * (e.g. via a wrapping {@code ModelProvider}); the {@code Retry-After} header is surfaced in error details when
 * present.
 */
public final class OpenAiProvider implements ModelProvider {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String providerId;
    private final String baseUrl;
    private final String apiKey;
    private final Duration defaultTimeout;
    private final HttpClient httpClient;

    private OpenAiProvider(
            String providerId, String baseUrl, String apiKey, Duration defaultTimeout, HttpClient httpClient) {
        this.providerId = providerId;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
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
                            "openai api returned a non-streaming response",
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
                    "openai request failed",
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
        Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        TokenUsage usage = null;
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
                if (data.isEmpty() || data.equals("[DONE]")) {
                    continue;
                }
                receivedAnyEvent = true;
                JsonNode node;
                try {
                    node = OBJECT_MAPPER.readTree(data);
                } catch (JsonProcessingException e) {
                    publisher.closeExceptionally(new ProviderException(
                            ErrorCode.PROVIDER_ERROR,
                            "malformed SSE event from openai",
                            Map.of("provider", providerId),
                            Map.of("line", data, "message", String.valueOf(e.getMessage())),
                            e));
                    return;
                }
                // Mid-stream API error payload: surface immediately instead of silently dropping.
                JsonNode errorNode = node.path("error");
                if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                    String message = errorNode.path("message").asText("openai stream error");
                    publisher.closeExceptionally(new ProviderException(
                            ErrorCode.PROVIDER_ERROR,
                            message,
                            Map.of("provider", providerId),
                            Map.of("error", errorNode.toString())));
                    return;
                }
                JsonNode choices = node.path("choices");
                JsonNode delta = choices.path(0).path("delta");
                JsonNode content = delta.path("content");
                if (content.isTextual() && !content.asText().isEmpty()) {
                    publisher.submit(new ModelStreamEvent.ContentDelta(content.asText()));
                }
                JsonNode toolCallDeltas = delta.path("tool_calls");
                if (toolCallDeltas.isArray()) {
                    for (JsonNode tc : toolCallDeltas) {
                        int index = tc.path("index").asInt(0);
                        ToolCallAccumulator acc = toolCalls.computeIfAbsent(index, k -> new ToolCallAccumulator());
                        JsonNode id = tc.path("id");
                        if (!id.isMissingNode() && !id.asText().isEmpty()) {
                            acc.id = id.asText();
                        }
                        JsonNode fn = tc.path("function");
                        JsonNode name = fn.path("name");
                        if (!name.isMissingNode() && !name.asText().isEmpty()) {
                            acc.name = name.asText();
                        }
                        JsonNode args = fn.path("arguments");
                        if (args.isTextual()) {
                            acc.arguments.append(args.asText());
                        }
                    }
                }
                String chunkFinish = choices.path(0).path("finish_reason").asText(null);
                if (chunkFinish != null && !chunkFinish.isEmpty() && !"null".equals(chunkFinish)) {
                    finishReason = chunkFinish;
                }
                if ("tool_calls".equals(finishReason)) {
                    for (ToolCallAccumulator acc : toolCalls.values()) {
                        publisher.submit(new ModelStreamEvent.ToolCallRequested(
                                acc.id, acc.name, parseArguments(acc.arguments.toString())));
                    }
                    toolCalls.clear();
                }
                JsonNode usageNode = node.path("usage");
                if (!usageNode.isMissingNode()) {
                    usage = parseUsage(usageNode);
                }
            }
        }
        if (!receivedAnyEvent) {
            publisher.closeExceptionally(new ProviderException(
                    ErrorCode.PROVIDER_ERROR,
                    "openai stream ended without any events",
                    Map.of("provider", providerId),
                    Map.of()));
            return;
        }
        for (ToolCallAccumulator acc : toolCalls.values()) {
            publisher.submit(
                    new ModelStreamEvent.ToolCallRequested(acc.id, acc.name, parseArguments(acc.arguments.toString())));
        }
        publisher.submit(new ModelStreamEvent.Completed(usage != null ? usage : new TokenUsage(0, 0), finishReason));
        publisher.close();
    }

    private HttpRequest buildRequest(ModelRequest request) throws IOException {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", request.model().modelId());
        body.put("stream", true);
        ObjectNode streamOptions = body.putObject("stream_options");
        streamOptions.put("include_usage", true);
        body.set("messages", buildMessages(request.instructions(), request.messages()));
        if (!request.tools().isEmpty()) {
            body.set("tools", buildTools(request.tools()));
        }
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                .build();
    }

    private ArrayNode buildMessages(String instructions, List<HelmMessage> messages) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        // Prepend a system message when the request carries instructions, mirroring the Anthropic provider.
        if (instructions != null && !instructions.isBlank()) {
            ObjectNode system = OBJECT_MAPPER.createObjectNode();
            system.put("role", "system");
            system.put("content", instructions);
            array.add(system);
        }
        for (HelmMessage message : messages) {
            for (ObjectNode node : toMessageNodes(message)) {
                array.add(node);
            }
        }
        return array;
    }

    /**
     * Expands a single {@link HelmMessage} into one or more OpenAI message nodes. A message containing multiple
     * {@link ToolResultBlock}s is split into one {@code tool} message per {@code tool_call_id}.
     */
    private List<ObjectNode> toMessageNodes(HelmMessage message) {
        List<ObjectNode> nodes = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = null;
        List<ToolResultBlock> toolResults = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case TextBlock t -> text.append(t.text());
                case ToolCallBlock tc -> {
                    if (toolCalls == null) {
                        toolCalls = OBJECT_MAPPER.createArrayNode();
                    }
                    ObjectNode tcNode = toolCalls.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode fn = tcNode.putObject("function");
                    fn.put("name", tc.name());
                    fn.put("arguments", tc.input() == null ? "" : writeJson(tc.input()));
                }
                case ToolResultBlock tr -> toolResults.add(tr);
            }
        }
        if (!toolResults.isEmpty()) {
            // Emit one tool message per tool_call_id; OpenAI does not batch multiple results into one message.
            for (ToolResultBlock tr : toolResults) {
                ObjectNode node = OBJECT_MAPPER.createObjectNode();
                node.put("role", "tool");
                node.put("tool_call_id", tr.toolCallId());
                node.put("content", serializeToolOutput(tr.output()));
                nodes.add(node);
            }
            return nodes;
        }
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("role", roleName(message.role()));
        if (toolCalls != null) {
            node.set("tool_calls", toolCalls);
            if (!text.isEmpty()) {
                node.put("content", text.toString());
            }
        } else {
            node.put("content", text.toString());
        }
        nodes.add(node);
        return nodes;
    }

    private ArrayNode buildTools(List<ToolDescriptor> tools) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        for (ToolDescriptor tool : tools) {
            ObjectNode entry = array.addObject();
            entry.put("type", "function");
            ObjectNode fn = entry.putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.set("parameters", toJsonNode(tool.inputSchema()));
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
            // OpenAI JSON Schema uses "type": ["string","null"] for nullable types.
            ArrayNode types = node.putArray("type");
            types.add(schema.type());
            types.add("null");
            node.remove("type");
            node.set("type", types);
        }
        return node;
    }

    private HelmException mapHttpError(int status, HttpHeaders headers, String body) {
        if (status == 400 && body != null && body.contains("context_length_exceeded")) {
            return ContextOverflowException.prompt(
                    "OpenAI context length exceeded", Map.of("provider", providerId, "status", status));
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
        return new ProviderException(code, "openai api error", details, Map.of("body", body));
    }

    private static TokenUsage parseUsage(JsonNode node) {
        long input = node.path("prompt_tokens").asLong(0);
        long output = node.path("completion_tokens").asLong(0);
        long cacheRead =
                node.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        long reasoning =
                node.path("completion_tokens_details").path("reasoning_tokens").asLong(0);
        return new TokenUsage(input, output, cacheRead, 0L, reasoning);
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
                        // HTTP-date form; not parsed to seconds here. Surfaced as-is in developer details via header.
                        return -1L;
                    }
                })
                .orElse(-1L);
    }

    private static String roleName(Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
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
        private String providerId = "openai";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey;
        private Duration defaultTimeout = Duration.ofSeconds(30);
        private HttpClient httpClient = HttpClient.newHttpClient();

        public Builder providerId(String providerId) {
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            return this;
        }

        /**
         * Sets the API base URL <b>including the {@code /v1} segment</b> (e.g. {@code https://api.openai.com/v1}).
         * Request paths ({@code /chat/completions}, {@code /embeddings}) are appended without re-adding {@code /v1}. A
         * trailing {@code /} is stripped.
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
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

        public OpenAiProvider build() {
            if (apiKey == null) {
                throw new IllegalArgumentException("apiKey is required");
            }
            String normalized = baseUrl;
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return new OpenAiProvider(providerId, normalized, apiKey, defaultTimeout, httpClient);
        }
    }
}
