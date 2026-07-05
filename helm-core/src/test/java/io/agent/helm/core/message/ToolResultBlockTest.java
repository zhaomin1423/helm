package io.agent.helm.core.message;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.agent.PromptStreamEvent;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

final class ToolResultBlockTest {
    @Test
    void fromCopiesAllToolResultFields() {
        ToolResult result = new ToolResult("call_1", "{\"ok\":true}", false);

        ToolResultBlock block = ToolResultBlock.from(result);

        assertThat(block.toolCallId()).isEqualTo("call_1");
        assertThat(block.output()).isEqualTo("{\"ok\":true}");
        assertThat(block.error()).isFalse();
    }

    @Test
    void toResultRoundTripsThroughFrom() {
        ToolResultBlock original = new ToolResultBlock("call_2", "err", true);

        ToolResult result = original.toResult();
        ToolResultBlock roundTripped = ToolResultBlock.from(result);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void modelStreamToolCallRequestedToBlockCopiesFields() {
        ModelStreamEvent.ToolCallRequested event =
                new ModelStreamEvent.ToolCallRequested("id", "echo", "{\"msg\":\"hi\"}");

        ToolCallBlock block = event.toBlock();

        assertThat(block.id()).isEqualTo("id");
        assertThat(block.name()).isEqualTo("echo");
        assertThat(block.input()).isEqualTo("{\"msg\":\"hi\"}");
    }

    @Test
    void promptStreamToolCallRequestedToBlockCopiesFields() {
        PromptStreamEvent.ToolCallRequested event = new PromptStreamEvent.ToolCallRequested("id2", "search", "q");

        ToolCallBlock block = event.toBlock();

        assertThat(block.id()).isEqualTo("id2");
        assertThat(block.name()).isEqualTo("search");
        assertThat(block.input()).isEqualTo("q");
    }
}
