package io.agent.helm.runtime;

import io.agent.helm.core.error.ProviderException;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelProviderContractTest;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import java.util.Map;

final class FakeProviderContractTest extends ModelProviderContractTest {
    private final FakeProvider provider = new FakeProvider("fake");

    @Override
    protected ModelProvider provider() {
        return provider;
    }

    @Override
    protected ModelRef supportedModel() {
        return ModelRef.parse("fake/test");
    }

    @Override
    protected void prepareTerminalTextStream(String text, TokenUsage usage) {
        provider.enqueue(new ModelStreamEvent.ContentDelta(text), new ModelStreamEvent.Completed(usage));
    }

    @Override
    protected void prepareToolCallStream(
            String toolCallId, String toolName, Object input, String finalText, TokenUsage usage) {
        provider.enqueue(
                new ModelStreamEvent.ToolCallRequested(toolCallId, toolName, input),
                new ModelStreamEvent.Completed(usage));
    }

    @Override
    protected void prepareErrorStream() {
        provider.failWith(new ProviderException("backend error", Map.of("status", 500), Map.of()));
    }
}
