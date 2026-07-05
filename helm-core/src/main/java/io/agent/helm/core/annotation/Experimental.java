package io.agent.helm.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an SPI or public API as experimental: the contract is being validated and may change between minor versions
 * based on adapter feedback. Unlike {@link Preview}, experimental APIs are expected to stabilize; they just haven't
 * yet. Implementors should track the corresponding contract test for the final shape.
 *
 * @since 0.2.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Experimental {
    String value() default "";
}
