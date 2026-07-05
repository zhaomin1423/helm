package io.agent.helm.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Covers {@link SseParser}: multi-line {@code data:} concatenation, blank-line frame splitting, sentinel skipping. */
final class SseParserTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SseParser parser = new SseParser(mapper);

    @Test
    void singleFrameSingleDataLine() {
        List<String> frames = parser.frames("data: hello\n\n");
        assertThat(frames).containsExactly("hello");
    }

    @Test
    void multipleFramesSplitByBlankLine() {
        String raw = "data: hello\n\ndata: world\n\n";
        List<String> frames = parser.frames(raw);
        assertThat(frames).containsExactly("hello", "world");
    }

    @Test
    void multiLineDataIsJoinedWithNewline() {
        String raw = "data: line1\ndata: line2\n\n";
        List<String> frames = parser.frames(raw);
        assertThat(frames).containsExactly("line1\nline2");
    }

    @Test
    void dataWithoutSpaceAfterColon() {
        List<String> frames = parser.frames("data:hello\n\n");
        assertThat(frames).containsExactly("hello");
    }

    @Test
    void trailingFrameWithoutClosingBlankIsEmitted() {
        List<String> frames = parser.frames("data: tail");
        assertThat(frames).containsExactly("tail");
    }

    @Test
    void emptyAndCommentLinesIgnored() {
        String raw = ": ping\ndata: keep\n\n:another comment\n\n";
        List<String> frames = parser.frames(raw);
        assertThat(frames).containsExactly("keep");
    }

    @Test
    void crlfLineEndingsSupported() {
        String raw = "data: a\r\n\r\ndata: b\r\n\r\n";
        List<String> frames = parser.frames(raw);
        assertThat(frames).containsExactly("a", "b");
    }

    @Test
    void eventAndIdFieldsIgnored() {
        String raw = "event: turn\nid: 1\ndata: {\"text\":\"hi\"}\n\n";
        List<String> frames = parser.frames(raw);
        assertThat(frames).containsExactly("{\"text\":\"hi\"}");
    }

    @Test
    void doneSentinelSkippedDuringParse() throws Exception {
        String raw =
                "data: {\"operationId\":\"op_1\",\"text\":\"hi\",\"totalUsage\":{\"inputTokens\":1,\"outputTokens\":1}}\n\ndata: [DONE]\n\n";
        List<PromptStreamEvent> events = parser.parse(raw);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(PromptStreamEvent.OperationCompleted.class);
    }

    @Test
    void parsesContentDeltaEvent() throws Exception {
        String raw = "data: " + mapper.writeValueAsString(new PromptStreamEvent.ContentDelta("hello")) + "\n\n";
        List<PromptStreamEvent> events = parser.parse(raw);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(PromptStreamEvent.ContentDelta.class);
        assertThat(((PromptStreamEvent.ContentDelta) events.get(0)).text()).isEqualTo("hello");
    }

    @Test
    void parsesOperationCompletedEvent() throws Exception {
        PromptStreamEvent.OperationCompleted event =
                new PromptStreamEvent.OperationCompleted("op_1", "done", new TokenUsage(2, 3));
        String raw = "data: " + mapper.writeValueAsString(event) + "\n\n";
        List<PromptStreamEvent> events = parser.parse(raw);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(PromptStreamEvent.OperationCompleted.class);
        PromptStreamEvent.OperationCompleted completed = (PromptStreamEvent.OperationCompleted) events.get(0);
        assertThat(completed.operationId()).isEqualTo("op_1");
        assertThat(completed.totalUsage().inputTokens()).isEqualTo(2L);
    }

    @Test
    void unparseableJsonWrapsAsContentDelta() {
        String raw = "data: not-json\n\n";
        List<PromptStreamEvent> events = parser.parse(raw);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(PromptStreamEvent.ContentDelta.class);
        assertThat(((PromptStreamEvent.ContentDelta) events.get(0)).text()).isEqualTo("not-json");
    }

    @Test
    void emptyInputReturnsEmptyList() {
        assertThat(parser.frames("")).isEmpty();
        assertThat(parser.frames(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
    }

    @Test
    void interleavedMultiLineFrames() {
        String raw = "data: a1\ndata: a2\n\ndata: b1\ndata: b2\n\n";
        List<String> frames = parser.frames(raw);
        assertThat(frames).containsExactly("a1\na2", "b1\nb2");
    }
}
