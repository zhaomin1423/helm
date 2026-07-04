package io.agent.helm.core.event;

public enum RuntimeEventType {
    OPERATION_STARTED("operation.started"),
    OPERATION_SUCCEEDED("operation.succeeded"),
    OPERATION_FAILED("operation.failed"),
    WORKFLOW_STARTED("workflow.started"),
    WORKFLOW_SUCCEEDED("workflow.succeeded"),
    WORKFLOW_FAILED("workflow.failed"),
    TURN_STARTED("turn.started"),
    TURN_SUCCEEDED("turn.succeeded"),
    TURN_FAILED("turn.failed"),
    MODEL_STARTED("model.started"),
    MODEL_SUCCEEDED("model.succeeded"),
    MODEL_FAILED("model.failed"),
    TOOL_STARTED("tool.started"),
    TOOL_SUCCEEDED("tool.succeeded"),
    TOOL_FAILED("tool.failed"),
    SKILL_STARTED("skill.started"),
    SKILL_SUCCEEDED("skill.succeeded"),
    SKILL_FAILED("skill.failed"),
    SANDBOX_COMMAND_STARTED("sandbox.command.started"),
    SANDBOX_COMMAND_SUCCEEDED("sandbox.command.succeeded"),
    SANDBOX_COMMAND_FAILED("sandbox.command.failed"),
    ERROR_RECORDED("error.recorded");

    private final String type;

    RuntimeEventType(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }
}
