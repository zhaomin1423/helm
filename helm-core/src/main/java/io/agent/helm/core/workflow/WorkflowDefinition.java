package io.agent.helm.core.workflow;

import io.agent.helm.core.type.TypeDescriptor;

public interface WorkflowDefinition<I, O> {
    String name();

    WorkflowConfig config();

    TypeDescriptor<I> inputType();

    TypeDescriptor<O> outputType();

    O run(WorkflowContext<I> context) throws Exception;
}
