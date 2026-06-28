package io.agent.helm.engine;

@FunctionalInterface
public interface ToolExecutor {
    Object execute(String operationId, String name, Object input);

    static ToolExecutor none() {
        return (operationId, name, input) -> {
            throw new IllegalStateException("No tool executor registered");
        };
    }
}
