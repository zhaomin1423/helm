package io.agent.helm.core.model;

import io.agent.helm.core.error.HelmException;
import java.util.concurrent.Flow;

public interface ModelProvider {
    boolean supports(ModelRef model);

    Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) throws HelmException;
}
