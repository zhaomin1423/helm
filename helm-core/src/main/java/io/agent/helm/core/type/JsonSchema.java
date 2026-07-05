package io.agent.helm.core.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * JSON Schema describing tool inputs. Built by reflection from {@link TypeDescriptor}; supports string, integer,
 * number, boolean, enum, record (object), {@code List<X>} / {@code Set<X>} (array), {@code Map<String, X>} (object with
 * additionalProperties), and {@code Optional<X>} (nullable, unwraps nested {@code Optional<Optional<X>>} and
 * {@code Optional<List<X>>}). Also maps common JDK value types: {@link java.util.UUID}, {@link java.net.URI},
 * {@link java.net.URL}, {@link java.math.BigDecimal}, {@link java.math.BigInteger}, and the {@code java.time} temporal
 * types ({@link java.time.Instant}, {@link java.time.LocalDate}, {@link java.time.LocalDateTime},
 * {@link java.time.OffsetDateTime}, {@link java.time.ZonedDateTime}, {@link java.time.Duration}) to {@code string} or
 * {@code number}. Record components may carry {@link SchemaDescription}.
 */
public record JsonSchema(
        String type,
        Map<String, JsonSchema> properties,
        List<String> required,
        JsonSchema items,
        String description,
        List<String> enumValues,
        boolean nullable,
        JsonSchema additionalProperties) {

    public JsonSchema {
        type = Objects.requireNonNull(type, "type");
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        required = List.copyOf(Objects.requireNonNull(required, "required"));
        enumValues = enumValues == null ? null : List.copyOf(enumValues);
    }

    // —— Basic type factories (signatures unchanged for backward compatibility) ——

    public static JsonSchema string() {
        return new JsonSchema("string", Map.of(), List.of(), null, null, null, false, null);
    }

    public static JsonSchema integer() {
        return new JsonSchema("integer", Map.of(), List.of(), null, null, null, false, null);
    }

    public static JsonSchema number() {
        return new JsonSchema("number", Map.of(), List.of(), null, null, null, false, null);
    }

    public static JsonSchema bool() {
        return new JsonSchema("boolean", Map.of(), List.of(), null, null, null, false, null);
    }

    public static JsonSchema object(Map<String, JsonSchema> properties, List<String> required) {
        return new JsonSchema("object", properties, required, null, null, null, false, null);
    }

    public static JsonSchema array(JsonSchema items) {
        return new JsonSchema("array", Map.of(), List.of(), items, null, null, false, null);
    }

    // —— Extended factories ——

    /** String schema restricted to the given enum values. */
    public static JsonSchema enumeration(String... values) {
        Objects.requireNonNull(values, "values");
        if (values.length == 0) {
            throw new IllegalArgumentException("enumValues must not be empty");
        }
        return new JsonSchema("string", Map.of(), List.of(), null, null, Arrays.asList(values), false, null);
    }

    /** Object schema allowing arbitrary string-keyed entries whose values conform to {@code valueSchema}. */
    public static JsonSchema map(JsonSchema valueSchema) {
        return new JsonSchema("object", Map.of(), List.of(), null, null, null, false, valueSchema);
    }

    // —— Wither methods ——

    public JsonSchema withDescription(String description) {
        return new JsonSchema(
                type, properties, required, items, description, enumValues, nullable, additionalProperties);
    }

    public JsonSchema withNullable() {
        return new JsonSchema(type, properties, required, items, description, enumValues, true, additionalProperties);
    }

    public static JsonSchema from(TypeDescriptor<?> descriptor) {
        return fromType(descriptor.type());
    }

    private static JsonSchema fromType(Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz == String.class) {
                return string();
            }
            if (clazz == int.class || clazz == Integer.class || clazz == long.class || clazz == Long.class) {
                return integer();
            }
            if (clazz == double.class || clazz == Double.class || clazz == float.class || clazz == Float.class) {
                return number();
            }
            if (clazz == boolean.class || clazz == Boolean.class) {
                return bool();
            }
            if (clazz == java.math.BigDecimal.class || clazz == java.math.BigInteger.class) {
                return number();
            }
            if (clazz == java.util.UUID.class || clazz == java.net.URI.class || clazz == java.net.URL.class) {
                return string();
            }
            if (clazz == java.time.Instant.class
                    || clazz == java.time.LocalDate.class
                    || clazz == java.time.LocalDateTime.class
                    || clazz == java.time.OffsetDateTime.class
                    || clazz == java.time.ZonedDateTime.class
                    || clazz == java.time.Duration.class) {
                return string();
            }
            if (clazz.isEnum()) {
                String[] names = Arrays.stream(clazz.getEnumConstants())
                        .map(Object::toString)
                        .toArray(String[]::new);
                return enumeration(names);
            }
            if (clazz.isRecord()) {
                return fromRecord(clazz);
            }
            // Raw List/Set/Map (no type arguments) fall through to permissive schemas.
            if (clazz == List.class || clazz == Set.class) {
                return array(null);
            }
            if (clazz == Map.class) {
                return object(Map.of(), List.of());
            }
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type raw = parameterizedType.getRawType();
            Type[] args = parameterizedType.getActualTypeArguments();
            if (raw == List.class || raw == Set.class) {
                if (args.length == 1 && args[0] != null) {
                    return array(fromType(args[0]));
                }
                return array(null);
            }
            if (raw == Map.class) {
                if (args.length == 2
                        && args[0] != null
                        && !(args[0] instanceof Class<?> c && c == String.class)
                        && !(args[0] instanceof ParameterizedType pt
                                && pt.getRawType() instanceof Class<?> rc
                                && rc == String.class)) {
                    throw new IllegalArgumentException("Map key must be String: " + type.getTypeName());
                }
                if (args.length == 2 && args[1] != null) {
                    return map(fromType(args[1]));
                }
                return object(Map.of(), List.of());
            }
            if (raw == Optional.class) {
                if (args.length == 1 && args[0] != null) {
                    return fromType(args[0]).withNullable();
                }
                return object(Map.of(), List.of()).withNullable();
            }
        }
        throw new IllegalArgumentException("Unsupported schema type: " + type.getTypeName());
    }

    private static JsonSchema fromRecord(Class<?> clazz) {
        Map<String, JsonSchema> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : clazz.getRecordComponents()) {
            Type genericType = component.getGenericType();
            JsonSchema propSchema = fromType(genericType);
            SchemaDescription desc = component.getAnnotation(SchemaDescription.class);
            if (desc != null) {
                propSchema = propSchema.withDescription(desc.value());
            }
            properties.put(component.getName(), propSchema);
            if (!isOptional(genericType)) {
                required.add(component.getName());
            }
        }
        return object(properties, required);
    }

    private static boolean isOptional(Type type) {
        return type instanceof ParameterizedType pt && pt.getRawType() == Optional.class;
    }
}
