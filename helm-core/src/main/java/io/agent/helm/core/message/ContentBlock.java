package io.agent.helm.core.message;

public sealed interface ContentBlock permits TextBlock, ToolCallBlock, ToolResultBlock {}
