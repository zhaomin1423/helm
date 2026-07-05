package io.agent.helm.observability.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies {@link RedactingEventRedactor} replaces {@link Redact}-annotated record components and secret map keys. */
final class RedactingEventRedactorTest {

    record CustomerInput(String customerId, @Redact("PII") String phoneNumber, String tier) {}

    record NestedRecord(String id, @Redact String secret, CustomerInput inner) {}

    record NoAnnotations(String a, String b) {}

    @Test
    void annotatedRecordComponentIsRedactedOthersPreserved() {
        CustomerInput input = new CustomerInput("c-1", "555-1234", "gold");
        CustomerInput redacted = (CustomerInput) RedactingEventRedactor.redact(input);

        assertThat(redacted.customerId()).isEqualTo("c-1");
        assertThat(redacted.phoneNumber()).isEqualTo("[REDACTED]");
        assertThat(redacted.tier()).isEqualTo("gold");
    }

    @Test
    void nestedRecordRedactionRecursesIntoAnnotatedComponent() {
        CustomerInput inner = new CustomerInput("c-1", "555-1234", "gold");
        NestedRecord record = new NestedRecord("rec-1", "top-secret-value", inner);
        NestedRecord redacted = (NestedRecord) RedactingEventRedactor.redact(record);

        assertThat(redacted.id()).isEqualTo("rec-1");
        assertThat(redacted.secret()).isEqualTo("[REDACTED]");
        assertThat(redacted.inner().phoneNumber()).isEqualTo("[REDACTED]");
        assertThat(redacted.inner().customerId()).isEqualTo("c-1");
    }

    @Test
    void recordWithoutAnnotationsIsUnchanged() {
        NoAnnotations record = new NoAnnotations("x", "y");
        NoAnnotations redacted = (NoAnnotations) RedactingEventRedactor.redact(record);
        assertThat(redacted).isEqualTo(record);
    }

    @Test
    void mapWithSecretKeysIsRedacted() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("authorization", "Bearer abc");
        payload.put("apiKey", "real-key");
        payload.put("normal", "kept");

        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) RedactingEventRedactor.redact(payload);

        assertThat(redacted.get("authorization")).isEqualTo("[REDACTED]");
        assertThat(redacted.get("apiKey")).isEqualTo("[REDACTED]");
        assertThat(redacted.get("normal")).isEqualTo("kept");
    }

    @Test
    void mapValueThatIsRecordIsRedactedRecursively() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", "lookup_customer");
        payload.put("input", new CustomerInput("c-1", "555-1234", "gold"));

        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) RedactingEventRedactor.redact(payload);

        CustomerInput redactedInput = (CustomerInput) redacted.get("input");
        assertThat(redactedInput.phoneNumber()).isEqualTo("[REDACTED]");
        assertThat(redactedInput.customerId()).isEqualTo("c-1");
        assertThat(redacted.get("tool")).isEqualTo("lookup_customer");
    }

    @Test
    void listWithRecordsIsRedactedRecursively() {
        Object redacted =
                RedactingEventRedactor.redact(List.of(new CustomerInput("c-1", "555-1234", "gold"), "scalar"));
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) redacted;
        CustomerInput first = (CustomerInput) list.get(0);
        assertThat(first.phoneNumber()).isEqualTo("[REDACTED]");
        assertThat(list.get(1)).isEqualTo("scalar");
    }

    @Test
    void nullAndPrimitiveValuesPassThrough() {
        assertThat(RedactingEventRedactor.redact(null)).isNull();
        assertThat(RedactingEventRedactor.redact("string")).isEqualTo("string");
        assertThat(RedactingEventRedactor.redact(42)).isEqualTo(42);
    }
}
