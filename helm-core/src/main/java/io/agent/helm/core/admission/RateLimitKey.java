package io.agent.helm.core.admission;

/** Key for a rate-limit dimension: per-principal, per-agent, per-session, or global. */
public record RateLimitKey(String dimension, String value) {
    public static final String PRINCIPAL = "PRINCIPAL";
    public static final String AGENT = "AGENT";
    public static final String SESSION = "SESSION";
    public static final String GLOBAL = "GLOBAL";

    public RateLimitKey {
        dimension = dimension == null ? GLOBAL : dimension;
        value = value == null ? "" : value;
    }

    public static RateLimitKey principal(String value) {
        return new RateLimitKey(PRINCIPAL, value);
    }

    public static RateLimitKey agent(String value) {
        return new RateLimitKey(AGENT, value);
    }

    public static RateLimitKey global() {
        return new RateLimitKey(GLOBAL, "");
    }
}
