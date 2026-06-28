package io.agent.helm.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class EventRedactor {
    private EventRedactor() {}

    static Map<String, Object> redact(Map<String, Object> payload) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        payload.forEach((key, value) -> redactEntry(redacted, String.valueOf(key), value));
        return Map.copyOf(redacted);
    }

    private static void redactEntry(Map<String, Object> target, String key, Object value) {
        if (isDeveloperDetails(key)) {
            return;
        }
        if (isSecretKey(key)) {
            target.put(key, "[REDACTED]");
            return;
        }
        target.put(key, redactValue(value));
    }

    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> redactEntry(nested, String.valueOf(nestedKey), nestedValue));
            return Map.copyOf(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> redacted = new ArrayList<>(list.size());
            for (Object item : list) {
                redacted.add(redactValue(item));
            }
            return List.copyOf(redacted);
        }
        return value;
    }

    private static boolean isDeveloperDetails(String key) {
        return normalizeKey(key).equals("developerdetails");
    }

    private static boolean isSecretKey(String key) {
        String normalized = normalizeKey(key);
        if (normalized.equals("authorization")
                || normalized.equals("token")
                || normalized.equals("password")
                || normalized.equals("secret")
                || normalized.equals("apikey")
                || normalized.equals("accesstoken")) {
            return true;
        }

        List<String> parts = keyParts(key);
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (part.equals("authorization")
                    || part.equals("token")
                    || part.equals("password")
                    || part.equals("secret")) {
                return true;
            }
            if (i + 1 < parts.size()) {
                String next = parts.get(i + 1);
                if (part.equals("api") && next.equals("key")) {
                    return true;
                }
                if (part.equals("access") && next.equals("token")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizeKey(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static List<String> keyParts(String key) {
        String separated = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String[] rawParts = separated.split("[^A-Za-z0-9]+");
        List<String> parts = new ArrayList<>(rawParts.length);
        for (String part : rawParts) {
            if (!part.isBlank()) {
                parts.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return parts;
    }
}
