package io.agent.helm.core.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TypeDescriptorTest {
    record Person(String name, int age) {}

    @Test
    void preservesGenericTypeInformation() {
        TypeDescriptor<List<Person>> descriptor = new TypeDescriptor<>() {};

        assertThat(descriptor.typeName()).contains("java.util.List");
        assertThat(descriptor.typeName()).contains("Person");
    }

    @Test
    void createsSchemaForRecordTypes() {
        JsonSchema schema = JsonSchema.from(TypeDescriptor.of(Person.class));

        assertThat(schema.type()).isEqualTo("object");
        assertThat(schema.properties()).containsKeys("name", "age");
        assertThat(schema.required()).containsExactly("name", "age");
    }

    @Test
    void createsSchemasForPrimitiveLikeAndListTypes() {
        assertThat(JsonSchema.from(TypeDescriptor.of(Double.class)).type()).isEqualTo("number");
        assertThat(JsonSchema.from(TypeDescriptor.of(Boolean.class)).type()).isEqualTo("boolean");

        JsonSchema listSchema = JsonSchema.from(new TypeDescriptor<List<String>>() {});

        assertThat(listSchema.type()).isEqualTo("array");
        assertThat(listSchema.items()).isNotNull();
        assertThat(listSchema.items().type()).isEqualTo("string");
    }

    @Test
    void supportsJdkValueAndTemporalTypes() {
        assertThat(JsonSchema.from(TypeDescriptor.of(java.util.UUID.class)).type())
                .isEqualTo("string");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.net.URI.class)).type())
                .isEqualTo("string");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.net.URL.class)).type())
                .isEqualTo("string");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.math.BigDecimal.class))
                        .type())
                .isEqualTo("number");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.math.BigInteger.class))
                        .type())
                .isEqualTo("number");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.time.Instant.class)).type())
                .isEqualTo("string");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.time.LocalDate.class)).type())
                .isEqualTo("string");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.time.LocalDateTime.class))
                        .type())
                .isEqualTo("string");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.time.OffsetDateTime.class))
                        .type())
                .isEqualTo("string");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.time.ZonedDateTime.class))
                        .type())
                .isEqualTo("string");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.time.Duration.class)).type())
                .isEqualTo("string");
    }

    @Test
    void supportsSetAndRawCollections() {
        JsonSchema setSchema = JsonSchema.from(new TypeDescriptor<java.util.Set<String>>() {});
        assertThat(setSchema.type()).isEqualTo("array");
        assertThat(setSchema.items()).isEqualTo(JsonSchema.string());

        // Raw List / Map (no type args) collapse to permissive array / object.
        assertThat(JsonSchema.from(TypeDescriptor.of(java.util.List.class)).type())
                .isEqualTo("array");
        assertThat(JsonSchema.from(TypeDescriptor.of(java.util.Map.class)).type())
                .isEqualTo("object");
    }

    @Test
    void supportsNestedOptionalUnwrapping() {
        TypeDescriptor<java.util.Optional<java.util.List<String>>> outerType = new TypeDescriptor<>() {};
        JsonSchema outer = JsonSchema.from(outerType);
        assertThat(outer.type()).isEqualTo("array");
        assertThat(outer.items()).isEqualTo(JsonSchema.string());
        assertThat(outer.nullable()).isTrue();

        TypeDescriptor<java.util.Optional<java.util.Optional<String>>> doubleOptType = new TypeDescriptor<>() {};
        JsonSchema doubleOpt = JsonSchema.from(doubleOptType);
        assertThat(doubleOpt.type()).isEqualTo("string");
        assertThat(doubleOpt.nullable()).isTrue();
    }

    @Test
    void rejectsUnsupportedTypes() {
        // UUID/Instant/Set now succeed; a genuinely unsupported type (raw Object) remains the rejection case.
        assertThatThrownBy(() -> JsonSchema.from(TypeDescriptor.of(Object.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported schema type");
    }

    @Test
    void defensivelyCopiesDirectConstructorArguments() {
        Map<String, JsonSchema> properties = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();
        properties.put("name", JsonSchema.string());
        required.add("name");

        JsonSchema schema = new JsonSchema("object", properties, required, null, null, null, false, null);
        properties.put("age", JsonSchema.integer());
        required.add("age");

        assertThat(schema.properties()).containsOnlyKeys("name");
        assertThat(schema.required()).containsExactly("name");
    }
}
