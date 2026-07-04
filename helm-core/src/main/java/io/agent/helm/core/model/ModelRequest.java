package io.agent.helm.core.model;

import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.tool.ToolDescriptor;
import java.time.Duration;
import java.util.List;

public record ModelRequest(
        ModelRef model, String instructions, List<ToolDescriptor> tools, List<HelmMessage> messages, Duration timeout) {
    public ModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
