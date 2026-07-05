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
    void rejectsUnsupportedTypes() {
        assertThatThrownBy(() -> JsonSchema.from(TypeDescriptor.of(java.util.UUID.class)))
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
