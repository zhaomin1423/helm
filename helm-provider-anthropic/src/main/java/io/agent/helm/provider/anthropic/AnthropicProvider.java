package io.agent.helm.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agent.helm.core.error.ContextOverflowException;
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

/**
 * A {@link ModelProvider} for the Anthropic Messages API. Event-typed SSE ({@code message_start},
 * {@code content_block_delta}, {@code message_stop}, ...) is normalized to Helm's {@link ModelStreamEvent} model.
 * {@code tool_use} content blocks and their {@code input_json_delta} fragments are aggregated before a
 * {@link ModelStreamEvent.ToolCallRequested} is emitted.
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
        // (and dropped) before onSubscribe.
        return subscriber -> {
            SubmissionPublisher<ModelStreamEvent> publisher = new SubmissionPublisher<>();
            publisher.subscribe(subscriber);
            Thread.startVirtualThread(() -> streamAsync(request, publisher));
        };
    }

    private void streamAsync(ModelRequest request, SubmissionPublisher<ModelStreamEvent> publisher) {
        try {
            HttpRequest httpRequest = buildRequest(request);
            HttpResponse<InputStream> response = httpClient.send(httpRequest, BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status >= 400) {
                    publisher.closeExceptionally(mapHttpError(status, readAll(body)));
                    return;
                }
                parseSse(body, publisher);
            }
        } catch (HttpTimeoutException e) {
            publisher.closeExceptionally(new ProviderException(
                    ProviderException.TIMEOUT, "request timed out", Map.of("provider", providerId), Map.of()));
        } catch (IOException e) {
            publisher.closeExceptionally(new ProviderException(
                    "anthropic request failed",
                    Map.of("provider", providerId),
                    Map.of("message", String.valueOf(e.getMessage()))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            publisher.closeExceptionally(
                    new ProviderException("request interrupted", Map.of("provider", providerId), Map.of()));
        } catch (RuntimeException e) {
            publisher.closeExceptionally(
                    e instanceof HelmException he
                            ? he
                            : new ProviderException(
                                    "unexpected error",
                                    Map.of("provider", providerId),
                                    Map.of("message", String.valueOf(e.getMessage()))));
        }
    }

    private void parseSse(InputStream body, SubmissionPublisher<ModelStreamEvent> publisher) throws IOException {
        Map<Integer, ToolCallAccumulator> tools = new LinkedHashMap<>();
        long inputTokens = 0;
        long outputTokens = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.isEmpty()) {
                    continue;
                }
                JsonNode node = OBJECT_MAPPER.readTree(data);
                String type = node.path("type").asText("");
                switch (type) {
                    case "message_start" -> inputTokens = node.path("message")
                            .path("usage")
                            .path("input_tokens")
                            .asLong(0);
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
                    case "message_delta" -> outputTokens =
                            node.path("usage").path("output_tokens").asLong(outputTokens);
                    case "message_stop" -> {
                        // Terminal event; completion is emitted after the loop.
                    }
                    default -> {
                        // Ignore unknown event types.
                    }
                }
            }
        }
        for (ToolCallAccumulator acc : tools.values()) {
            publisher.submit(
                    new ModelStreamEvent.ToolCallRequested(acc.id, acc.name, parseArguments(acc.arguments.toString())));
        }
        publisher.submit(new ModelStreamEvent.Completed(new TokenUsage(inputTokens, outputTokens)));
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
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("Content-Type", "application/json")
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
                    result.put("content", tr.output() == null ? "" : writeJson(tr.output()));
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
        return node;
    }

    private HelmException mapHttpError(int status, String body) {
        if (status == 400 && body != null && body.contains("prompt_too_long")) {
            return ContextOverflowException.prompt(
                    "Anthropic prompt too long", Map.of("provider", providerId, "status", status));
        }
        String code = ProviderException.CODE;
        if (status == 429) {
            code = ProviderException.RATE_LIMITED;
        }
        return new ProviderException(
                code, "anthropic api error", Map.of("provider", providerId, "status", status), Map.of("body", body));
    }

    private static Object parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(arguments, Object.class);
        } catch (Exception e) {
            return arguments;
        }
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

    private static final class ToolCallAccumulator {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }

    public static final class Builder {
        private String providerId = "anthropic";
        private String baseUrl = "https://api.anthropic.com";
        private String apiKey;
        private int maxTokens = 1024;
        private Duration defaultTimeout = Duration.ofSeconds(30);
        private HttpClient httpClient = HttpClient.newHttpClient();

        public Builder providerId(String providerId) {
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            return this;
        }

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
            return new AnthropicProvider(providerId, baseUrl, apiKey, maxTokens, defaultTimeout, httpClient);
        }
    }
}
