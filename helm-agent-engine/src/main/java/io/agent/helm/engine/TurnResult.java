package io.agent.helm.engine;

import io.agent.helm.core.model.ModelStreamEvent;
import java.util.List;

record TurnResult(String text, List<ModelStreamEvent.ToolCallRequested> toolCalls) {}
