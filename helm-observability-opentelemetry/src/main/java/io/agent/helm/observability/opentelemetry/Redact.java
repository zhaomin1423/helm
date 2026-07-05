package io.agent.helm.observability.opentelemetry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component or field as sensitive so that {@link RedactingEventRedactor} replaces its value with
 * {@code [REDACTED]} before the value is emitted into an event payload, span attribute, or metric label.
 *
 * <p>This annotation lives in the OpenTelemetry adapter module because Helm's core contract deliberately keeps no
 * dependency on observability tooling. Tool authors who want to mark business records without depending on this module
 * can copy the annotation shape into their own packages — the redactor matches purely on the simple class name
 * {@code Redact} so user-defined copies in tool packages are also honored.
 *
 * <p>Apply to tool input/output record components that carry PII (phone numbers, emails, addresses) or secrets the
 * runtime should never persist verbatim. The redactor recurses through nested records, maps, and lists.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Redact {
    /** Optional reason surfaced only in developer tooling, never in events or attributes. */
    String value() default "";
}
