package io.agent.helm.core.error;

import java.util.Map;

/**
 * Raised when the conversation context exceeds the model's token budget. The {@code kind} detail distinguishes where
 * the overflow occurred: prompt input, single-turn completion, or accumulated history.
 */
public final class ContextOverflowException extends HelmException {

    public static final String PROMPT_OVERFLOW = "PROMPT_OVERFLOW";
    public static final String COMPLETION_OVERFLOW = "COMPLETION_OVERFLOW";
    public static final String ACCUMULATED_OVERFLOW = "ACCUMULATED_OVERFLOW";

    public ContextOverflowException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(ErrorCode.CONTEXT_OVERFLOW, message, details, developerDetails);
    }

    public ContextOverflowException(
            String message, Map<String, Object> details, Map<String, Object> developerDetails, Throwable cause) {
        super(ErrorCode.CONTEXT_OVERFLOW, message, details, developerDetails, cause);
    }

    /** Input prompt tokens exceed the model limit. */
    public static ContextOverflowException prompt(String message, Map<String, Object> details) {
        return new ContextOverflowException(message, withKind(details, PROMPT_OVERFLOW), Map.of());
    }

    /** A single model completion exceeded the model's output token limit. */
    public static ContextOverflowException completion(String message, Map<String, Object> details) {
        return new ContextOverflowException(message, withKind(details, COMPLETION_OVERFLOW), Map.of());
    }

    /** Accumulated conversation history exceeds the model's context window. */
    public static ContextOverflowException accumulated(String message, Map<String, Object> details) {
        return new ContextOverflowException(message, withKind(details, ACCUMULATED_OVERFLOW), Map.of());
    }

    private static Map<String, Object> withKind(Map<String, Object> details, String kind) {
        Map<String, Object> copy = copySafe(details);
        Map<String, Object> merged = new java.util.HashMap<>(copy);
        merged.put("kind", kind);
        return java.util.Collections.unmodifiableMap(merged);
    }
}
