package io.agent.helm.core.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Covers JsonSchema reflection for basic types, enum, record, Map, List, Optional, and @SchemaDescription. */
class JsonSchemaTest {

    enum Color {
        RED,
        GREEN,
        BLUE
    }

    record WithMap(@SchemaDescription("tagged values") Map<String, Integer> tags) {}

    record WithEnum(Color color) {}

    record WithOptional(String required, Optional<String> optional) {}

    record WithList(List<String> names) {}

    record Nested(WithEnum child, WithList lists) {}

    @Test
    void basicTypes() {
        assertThat(JsonSchema.string().type()).isEqualTo("string");
        assertThat(JsonSchema.integer().type()).isEqualTo("integer");
        assertThat(JsonSchema.number().type()).isEqualTo("number");
        assertThat(JsonSchema.bool().type()).isEqualTo("boolean");
    }

    @Test
    void enumSchemaFromEnumClass() {
        JsonSchema schema = JsonSchema.from(TypeDescriptor.of(Color.class));
        assertThat(schema.type()).isEqualTo("string");
        assertThat(schema.enumValues()).containsExactly("RED", "GREEN", "BLUE");
    }

    @Test
    void recordWithMapProducesAdditionalProperties() {
        JsonSchema schema = JsonSchema.from(TypeDescriptor.of(WithMap.class));
        assertThat(schema.type()).isEqualTo("object");
        JsonSchema tagsProp = schema.properties().get("tags");
        assertThat(tagsProp.type()).isEqualTo("object");
        assertThat(tagsProp.additionalProperties()).isEqualTo(JsonSchema.integer());
        assertThat(tagsProp.description()).isEqualTo("tagged values");
    }

    @Test
    void recordWithEnumProperty() {
        JsonSchema schema = JsonSchema.from(TypeDescriptor.of(WithEnum.class));
        JsonSchema colorProp = schema.properties().get("color");
        assertThat(colorProp.enumValues()).containsExactly("RED", "GREEN", "BLUE");
        assertThat(schema.required()).contains("color");
    }

    @Test
    void optionalFieldIsNullableAndNotRequired() {
        JsonSchema schema = JsonSchema.from(TypeDescriptor.of(WithOptional.class));
        assertThat(schema.required()).contains("required").doesNotContain("optional");
        assertThat(schema.properties().get("optional").nullable()).isTrue();
        assertThat(schema.properties().get("required").nullable()).isFalse();
    }

    @Test
    void listPropertyHasItemsSchema() {
        JsonSchema schema = JsonSchema.from(TypeDescriptor.of(WithList.class));
        JsonSchema namesProp = schema.properties().get("names");
        assertThat(namesProp.type()).isEqualTo("array");
        assertThat(namesProp.items()).isEqualTo(JsonSchema.string());
    }

    @Test
    void nestedRecordsResolveRecursively() {
        JsonSchema schema = JsonSchema.from(TypeDescriptor.of(Nested.class));
        assertThat(schema.properties().get("child").type()).isEqualTo("object");
        assertThat(schema.properties().get("lists").properties().get("names").type())
                .isEqualTo("array");
    }

    @Test
    void unsupportedTypeFailsFast() {
        assertThatThrownBy(() -> JsonSchema.from(TypeDescriptor.of(Thread.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported schema type");
    }
}
