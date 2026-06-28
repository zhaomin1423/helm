package io.agent.helm.core.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

public class TypeDescriptor<T> {
    private final Type type;

    protected TypeDescriptor() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType parameterizedType)) {
            throw new IllegalStateException("TypeDescriptor anonymous subclass must preserve generic type");
        }
        this.type = parameterizedType.getActualTypeArguments()[0];
    }

    private TypeDescriptor(Type type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public static <T> TypeDescriptor<T> of(Class<T> type) {
        return new TypeDescriptor<>(type);
    }

    public Type type() {
        return type;
    }

    public String typeName() {
        return type.getTypeName();
    }
}
