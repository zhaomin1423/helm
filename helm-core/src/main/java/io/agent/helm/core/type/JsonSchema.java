package io.agent.helm.core.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record JsonSchema(String type, Map<String, JsonSchema> properties, List<String> required, JsonSchema items) {
    public JsonSchema {
        type = Objects.requireNonNull(type, "type");
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        required = List.copyOf(Objects.requireNonNull(required, "required"));
    }

    public static JsonSchema string() {
        return new JsonSchema("string", Map.of(), List.of(), null);
    }

    public static JsonSchema integer() {
        return new JsonSchema("integer", Map.of(), List.of(), null);
    }

    public static JsonSchema number() {
        return new JsonSchema("number", Map.of(), List.of(), null);
    }

    public static JsonSchema bool() {
        return new JsonSchema("boolean", Map.of(), List.of(), null);
    }

    public static JsonSchema object(Map<String, JsonSchema> properties, List<String> required) {
        return new JsonSchema("object", Map.copyOf(properties), List.copyOf(required), null);
    }

    public static JsonSchema array(JsonSchema items) {
        return new JsonSchema("array", Map.of(), List.of(), items);
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
            if (clazz.isRecord()) {
                Map<String, JsonSchema> properties = new LinkedHashMap<>();
                for (RecordComponent component : clazz.getRecordComponents()) {
                    properties.put(component.getName(), fromType(component.getGenericType()));
                }
                return object(properties, List.copyOf(properties.keySet()));
            }
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() == List.class) {
            return array(fromType(parameterizedType.getActualTypeArguments()[0]));
        }
        throw new IllegalArgumentException("Unsupported schema type: " + type.getTypeName());
    }
}
