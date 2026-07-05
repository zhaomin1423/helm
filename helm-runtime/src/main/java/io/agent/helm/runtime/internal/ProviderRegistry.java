package io.agent.helm.runtime.internal;

import io.agent.helm.core.error.ProviderNotFoundException;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import java.util.List;
import java.util.Map;

public final class ProviderRegistry {
    private final List<ModelProvider> providers;

    public ProviderRegistry(List<ModelProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public ModelProvider resolve(ModelRef model) {
        return providers.stream()
                .filter(provider -> provider.supports(model))
                .findFirst()
                .orElseThrow(() -> new ProviderNotFoundException(
                        "No provider for model", Map.of("model", model.value()), Map.of()));
    }
}
