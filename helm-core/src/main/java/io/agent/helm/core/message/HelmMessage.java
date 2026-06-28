package io.agent.helm.core.message;

import java.util.List;

public record HelmMessage(Role role, List<ContentBlock> content) {
    public HelmMessage {
        content = List.copyOf(content);
    }

    public static HelmMessage user(String text) {
        return new HelmMessage(Role.USER, List.of(new TextBlock(text)));
    }

    public static HelmMessage assistant(String text) {
        return new HelmMessage(Role.ASSISTANT, List.of(new TextBlock(text)));
    }
}
