package io.agent.helm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code text/event-stream} into {@link PromptStreamEvent}s.
 *
 * <p>Implements the subset of the SSE wire format used by Helm streaming routes: frames separated by blank lines,
 * {@code data:} lines joined with {@code \n} within a frame, {@code event:}/{@code id:}/{@code retry:}/{@code :comment}
 * lines ignored, and a trailing {@code data: [DONE]} sentinel skipped.
 *
 * <p>Each {@code data} payload is a JSON object. Because {@link PromptStreamEvent} is a sealed interface without
 * Jackson type-info (core has no Jackson dependency), the parser inspects the JSON fields to select the subtype rather
 * than relying on polymorphic deserialization — so it works regardless of whether the producer added a type
 * discriminator.
 */
final class SseParser {

    private final ObjectMapper mapper;

    SseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Extracts raw {@code data} payloads, one per frame. */
    List<String> frames(String raw) {
        List<String> frames = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return frames;
        }
        StringBuilder current = new StringBuilder();
        boolean hasData = false;
        for (String line : raw.split("\r?\n", -1)) {
            if (line.isEmpty()) {
                if (hasData) {
                    frames.add(current.toString());
                    current.setLength(0);
                    hasData = false;
                }
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("data:")) {
                String payload = line.substring(5);
                if (payload.startsWith(" ")) {
                    payload = payload.substring(1);
                }
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(payload);
                hasData = true;
            }
            // event:/id:/retry:/unknown fields are ignored
        }
        if (hasData) {
            // Trailing frame without a closing blank line — emit it.
            frames.add(current.toString());
        }
        return frames;
    }

    /** Parses all frames into typed events, skipping {@code [DONE]} sentinels and unparseable payloads. */
    List<PromptStreamEvent> parse(String raw) {
        List<PromptStreamEvent> events = new ArrayList<>();
        for (String frame : frames(raw)) {
            String trimmed = frame.trim();
            if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
                continue;
            }
            PromptStreamEvent event = parseFrame(trimmed);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    private PromptStreamEvent parseFrame(String frame) {
        try {
            JsonNode node = mapper.readTree(frame);
            if (node.has("operationId") && node.has("totalUsage")) {
                JsonNode usage = node.path("totalUsage");
                return new PromptStreamEvent.OperationCompleted(
                        node.path("operationId").asText(),
                        node.path("text").asText(),
                        new TokenUsage(
                                usage.path("inputTokens").asLong(),
                                usage.path("outputTokens").asLong()));
            }
            if (node.has("operationId") && node.has("code")) {
                return new PromptStreamEvent.OperationFailed(
                        node.path("operationId").asText(),
                        node.path("code").asText(),
                        node.path("message").asText());
            }
            if (node.has("turnIndex") && node.has("usage")) {
                JsonNode usage = node.path("usage");
                return new PromptStreamEvent.TurnEnded(
                        node.path("turnIndex").asInt(),
                        new TokenUsage(
                                usage.path("inputTokens").asLong(),
                                usage.path("outputTokens").asLong()));
            }
            if (node.has("id") && node.has("name")) {
                if (node.has("input")) {
                    return new PromptStreamEvent.ToolCallRequested(
                            node.path("id").asText(),
                            node.path("name").asText(),
                            mapper.convertValue(node.path("input"), Object.class));
                }
                if (node.has("output")) {
                    return new PromptStreamEvent.ToolResultReady(
                            node.path("id").asText(),
                            node.path("name").asText(),
                            mapper.convertValue(node.path("output"), Object.class));
                }
            }
            if (node.has("text")) {
                return new PromptStreamEvent.ContentDelta(node.path("text").asText());
            }
        } catch (Exception ignored) {
            // not JSON — fall through to raw text delta
        }
        return new PromptStreamEvent.ContentDelta(frame);
    }
}
