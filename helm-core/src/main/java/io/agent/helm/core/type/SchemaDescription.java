package io.agent.helm.core.type;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attaches a human-readable description to a record component so {@link JsonSchema#from} can populate the
 * {@code description} field of the generated schema. Provider adapters translate this to OpenAI / Anthropic tool schema
 * descriptions.
 *
 * @since 0.2.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface SchemaDescription {
    String value();
}
