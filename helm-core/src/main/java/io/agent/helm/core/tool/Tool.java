package io.agent.helm.core.tool;

import io.agent.helm.core.type.JsonSchema;
import io.agent.helm.core.type.TypeDescriptor;

public interface Tool<I, O> {
    String name();

    /** Human-readable description used to advertise the tool to model providers. */
    default String description() {
        return "";
    }

    TypeDescriptor<I> inputType();

    TypeDescriptor<O> outputType();

    default JsonSchema inputSchema() {
        return JsonSchema.from(inputType());
    }

    O execute(ToolContext context, I input) throws Exception;
}
