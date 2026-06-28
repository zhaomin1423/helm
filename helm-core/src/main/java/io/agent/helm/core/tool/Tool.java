package io.agent.helm.core.tool;

import io.agent.helm.core.type.JsonSchema;
import io.agent.helm.core.type.TypeDescriptor;

public interface Tool<I, O> {
    String name();

    TypeDescriptor<I> inputType();

    TypeDescriptor<O> outputType();

    default JsonSchema inputSchema() {
        return JsonSchema.from(inputType());
    }

    O execute(ToolContext context, I input) throws Exception;
}
