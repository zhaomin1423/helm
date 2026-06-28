package io.agent.helm.core.error;

import java.util.Map;

public final class SandboxException extends HelmException {
    public SandboxException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("SANDBOX_ERROR", message, details, developerDetails);
    }
}
