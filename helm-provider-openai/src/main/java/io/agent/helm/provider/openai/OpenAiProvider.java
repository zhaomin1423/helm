package io.agent.helm.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * A {@link ModelProvider} for OpenAI-compatible Chat Completions endpoints (configurable base URL, so vLLM, Ollama, and
 * other compatible servers work). Streaming responses (SSE) are normalized to Helm's {@link ModelStreamEvent} model;
 * tool-call argument fragments are aggregated before a {@link ModelStreamEvent.ToolCallRequested} is emitted.
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
        SubmissionPublisher<ModelStreamEvent> publisher = new SubmissionPublisher<>();
        Thread.startVirtualThread(() -> streamAsync(request, publisher));
        return publisher;
    }

    private void streamAsync(ModelRequest request, SubmissionPublisher<ModelStreamEvent> publisher) {
        try {
            HttpRequest httpRequest = buildRequest(request);
            HttpResponse<InputStream> response = httpClient.send(httpRequest, BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 400) {
                String body = readAll(response.body());
                publisher.closeExceptionally(mapHttpError(status, body));
                return;
            }
            parseSse(response.body(), publisher);
        } catch (HttpTimeoutException e) {
            publisher.closeExceptionally(new ProviderException(
                    ProviderException.TIMEOUT, "request timed out", Map.of("provider", providerId), Map.of()));
        } catch (IOException e) {
            publisher.closeExceptionally(new ProviderException(
                    "openai request failed",
                    Map.of("provider", providerId),
                    Map.of("message", String.valueOf(e.getMessage()))));
        } catch (Exception e) {
            publisher.closeExceptionally(
                    e instanceof RuntimeException re
                            ? re
                            : new ProviderException("unexpected error", Map.of("provider", providerId), Map.of()));
        }
    }

    private void parseSse(InputStream body, SubmissionPublisher<ModelStreamEvent> publisher) throws IOException {
        Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        TokenUsage usage = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).strip();
            if (data.isEmpty() || data.equals("[DONE]")) {
                continue;
            }
            JsonNode node = OBJECT_MAPPER.readTree(data);
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
            String finishReason = choices.path(0).path("finish_reason").asText(null);
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
        for (ToolCallAccumulator acc : toolCalls.values()) {
            publisher.submit(
                    new ModelStreamEvent.ToolCallRequested(acc.id, acc.name, parseArguments(acc.arguments.toString())));
        }
        publisher.submit(new ModelStreamEvent.Completed(usage != null ? usage : new TokenUsage(0, 0)));
        publisher.close();
    }

    private HttpRequest buildRequest(ModelRequest request) throws IOException {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", request.model().modelId());
        body.put("stream", true);
        ObjectNode streamOptions = body.putObject("stream_options");
        streamOptions.put("include_usage", true);
        body.set("messages", buildMessages(request.messages()));
        if (!request.tools().isEmpty()) {
            body.set("tools", buildTools(request.tools()));
        }
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                .build();
    }

    private ArrayNode buildMessages(List<HelmMessage> messages) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        for (HelmMessage message : messages) {
            array.add(toMessageNode(message));
        }
        return array;
    }

    private ObjectNode toMessageNode(HelmMessage message) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("role", roleName(message.role()));
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = null;
        String toolCallId = null;
        Object toolOutput = null;
        for (ContentBlock block : message.content()) {
            switch (block) {
                case TextBlock t -> text.append(t.text());
                case ToolCallBlock tc -> {
                    if (toolCalls == null) {
                        toolCalls = node.putArray("tool_calls");
                    }
                    ObjectNode tcNode = toolCalls.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode fn = tcNode.putObject("function");
                    fn.put("name", tc.name());
                    fn.put("arguments", tc.input() == null ? "" : writeJson(tc.input()));
                }
                case ToolResultBlock tr -> {
                    toolCallId = tr.toolCallId();
                    toolOutput = tr.output();
                }
            }
        }
        if (toolCallId != null) {
            node.put("tool_call_id", toolCallId);
            node.put("content", toolOutput == null ? "" : writeJson(toolOutput));
        } else if (toolCalls != null) {
            if (!text.isEmpty()) {
                node.put("content", text.toString());
            }
        } else {
            node.put("content", text.toString());
        }
        return node;
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

    private ProviderException mapHttpError(int status, String body) {
        String code = ProviderException.CODE;
        if (status == 429) {
            code = ProviderException.RATE_LIMITED;
        }
        return new ProviderException(
                code, "openai api error", Map.of("provider", providerId, "status", status), Map.of("body", body));
    }

    private static TokenUsage parseUsage(JsonNode node) {
        long input = node.path("prompt_tokens").asLong(0);
        long output = node.path("completion_tokens").asLong(0);
        return new TokenUsage(input, output);
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
            return new OpenAiProvider(providerId, baseUrl, apiKey, defaultTimeout, httpClient);
        }
    }
}
