package io.agent.helm.core.error;

import java.util.Map;

public final class ProviderNotFoundException extends HelmException {
    public ProviderNotFoundException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("PROVIDER_NOT_FOUND", message, details, developerDetails);
    }
}
