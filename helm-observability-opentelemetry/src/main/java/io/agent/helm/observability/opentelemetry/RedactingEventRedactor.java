package io.agent.helm.observability.opentelemetry;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursively redacts fields annotated with {@link Redact} from records, maps, and lists before they are emitted into
 * runtime event payloads or span attributes. Heuristic key-name redaction ({@code authorization}, {@code token},
 * {@code password}, {@code secret}, {@code apiKey}, {@code accessToken}) is also applied so that this adapter never
 * re-introduces sensitive content the core/runtime already strips.
 *
 * <p>The core runtime's own {@code EventRedactor} runs before observers receive events, so this redactor is intended
 * for adapter-local content capture (e.g. {@link OpenTelemetryRuntimeObserver} when {@link ContentCaptureLevel#FULL}
 * records tool input/output as span attributes). It must never throw — a reflective failure falls back to redacting the
 * whole record so the safe default holds.
 */
public final class RedactingEventRedactor {
    private static final String REDACTED = "[REDACTED]";

    private RedactingEventRedactor() {}

    /**
     * Recursively redacts the supplied value. Records have {@link Redact}-annotated components replaced; maps/lists are
     * traversed; primitives are returned unchanged. Heuristically-secret map keys are always redacted.
     */
    public static Object redact(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> redacted = new LinkedHashMap<>();
            map.forEach((k, v) -> redacted.put(String.valueOf(k), redactEntry(String.valueOf(k), v)));
            return Map.copyOf(redacted);
        }
        if (value instanceof List<?> list) {
            List<Object> redacted = new ArrayList<>(list.size());
            for (Object item : list) {
                redacted.add(redact(item));
            }
            return List.copyOf(redacted);
        }
        if (value.getClass().isRecord()) {
            return redactRecord(value);
        }
        return value;
    }

    private static Object redactEntry(String key, Object value) {
        if (isSecretKey(key)) {
            return REDACTED;
        }
        return redact(value);
    }

    private static Object redactRecord(Object record) {
        Class<?> type = record.getClass();
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            Object componentValue;
            try {
                componentValue = c.getAccessor().invoke(record);
            } catch (ReflectiveOperationException e) {
                // Reflective failure must never leak the original value; redact the whole record.
                return REDACTED;
            }
            if (isRedactAnnotated(c)) {
                args[i] = REDACTED;
            } else if (componentValue != null && componentValue.getClass().isRecord()) {
                args[i] = redactRecord(componentValue);
            } else {
                args[i] = redact(componentValue);
            }
        }
        try {
            return type.getDeclaredConstructor(componentTypes(components)).newInstance(args);
        } catch (ReflectiveOperationException e) {
            return REDACTED;
        }
    }

    private static boolean isRedactAnnotated(RecordComponent component) {
        for (java.lang.annotation.Annotation annotation : component.getAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals("Redact")) {
                return true;
            }
        }
        return false;
    }

    private static Class<?>[] componentTypes(RecordComponent[] components) {
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
        }
        return types;
    }

    private static boolean isSecretKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", "")
                .toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("authorization")
                || normalized.equals("token")
                || normalized.equals("password")
                || normalized.equals("secret")
                || normalized.equals("apikey")
                || normalized.equals("accesstoken");
    }
}
