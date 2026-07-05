package io.agent.helm.core.error;

import java.util.Map;

public final class SandboxException extends HelmException {
    public SandboxException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.SANDBOX_ERROR, message, details, developerDetails);
    }

    public SandboxException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.SANDBOX_ERROR, message, details, developerDetails, cause);
    }
}
