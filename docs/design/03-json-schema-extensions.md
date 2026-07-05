# 03 — JsonSchema 类型扩展

> 组件编号：3　　来源 milestone：M3（`docs/roadmap.md` 第 5 节 M3 交付项「`JsonSchema` 扩展 Map、enum、nested record、optional/nullability」）　　状态：proposed
>
> 依赖守则：本组件只动 `helm-core` 的 `core.type` 子包与 provider 的 schema 映射方法；不引入 core 禁止依赖（Spring/Servlet/JDBC/SDK/logging）。新增 SPI/扩展点配 `JsonSchemaContractTest`（test-jar）。

---

## 1. 背景与目标

`JsonSchema` 是 Helm 把 Java 类型（`TypeDescriptor`）映射到 provider tool input schema 的唯一桥梁。它当前只覆盖了最基本的 Java 类型子集，导致大量常见 tool 入参形态无法表达：

- `Map<String, X>` —— 元数据、标签、动态属性。
- `enum` —— 状态机、离散动作选择。
- `Optional<X>` / 可空字段 —— 不强制 required 的入参。
- 字段级 `description` —— 让模型理解每个参数语义。
- `additionalProperties` —— 表达开放对象。

本组件扩展 `JsonSchema` record 与 `fromType` 反射逻辑，使其能完整描述 Java 21 record + 常见容器类型，并把新增字段映射到 OpenAI function parameters 与 Anthropic input_schema。

**目标**

1. `JsonSchema` record 不可变、向后兼容地新增 `description`、`enumValues`、`nullable`、`additionalProperties` 四个字段。
2. `JsonSchema.from(TypeDescriptor)` 自动识别 `Map<String,X>`、`enum`、`Optional<X>`、嵌套泛型（`List<List<X>>`、record 内泛型字段）。
3. 字段描述来自 record component 上的 `@SchemaDescription` 注解（新增到 `core.type`）。
4. OpenAI / Anthropic 两个 provider 的 schema 映射方法同步扩展，并显式处理两个 provider 的能力差异（Anthropic 不支持 `additionalProperties` 时的回退策略）。
5. 提供 `JsonSchemaContractTest` 抽象基类（随 helm-core test-jar 发布），覆盖每种类型的 round-trip。

**非目标**

- 不引入 JSON Schema Draft 2020-12 全集（`$ref`、`allOf`、`oneOf`、`pattern`、`format` 等）。本组件只补 M3 列出的缺口。
- 不实现运行时 JSON 校验器；schema 仅用于 provider 描述与 engine hardening #2 的 tool input 形态校验（结构层面，非值层面）。
- 不替换 `TypeDescriptor` 的反射机制（保留 `ParameterizedType` / `RecordComponent` 反射）。

---

## 2. 现状与缺口

### 2.1 当前 `JsonSchema` 定义

`helm-core/src/main/java/io/agent/helm/core/type/JsonSchema.java:11`：

```java
public record JsonSchema(
        String type,
        Map<String, JsonSchema> properties,
        List<String> required,
        JsonSchema items) {
    public JsonSchema {
        type = Objects.requireNonNull(type, "type");
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        required = List.copyOf(Objects.requireNonNull(required, "required"));
    }
    // 工厂方法 string()/integer()/number()/bool()/object(...)/array(...)
    // from(TypeDescriptor) -> fromType(Type)
}
```

`fromType` 当前覆盖：

| Java 类型 | 当前行为 | 文件位置 |
| --- | --- | --- |
| `String` / `int` / `Integer` / `long` / `Long` | `string` / `integer` | `JsonSchema.java:48-52` |
| `double` / `Double` / `float` / `Float` | `number` | `JsonSchema.java:54-56` |
| `boolean` / `Boolean` | `boolean` | `JsonSchema.java:57-59` |
| record | `object`，所有字段进 `required` | `JsonSchema.java:60-66` |
| `List<X>` | `array` + `items` 递归 | `JsonSchema.java:68-70` |
| 其他 | `throw new IllegalArgumentException("Unsupported schema type: " + type.getTypeName())` | `JsonSchema.java:71` |

### 2.2 缺口（对照 roadmap M3）

来源：`docs/roadmap.md:212` —— `JsonSchema 扩展 Map、enum、nested record、optional/nullability`。

| 缺口 | 现状 | 影响 |
| --- | --- | --- |
| `Map<String, X>` | `fromType` 直接抛 `Unsupported schema type` | 含 `Map` 字段的 tool record 无法注册（`Tool.inputSchema()` 抛错） |
| `enum` | 同上抛错 | 状态/动作类入参无法表达 |
| `Optional<X>` / 可空字段 | 同上抛错；且 record 所有字段强制 `required` | 可选入参无法表达 |
| 字段级 `description` | 无字段；provider `toJsonNode` 也不输出 | 模型看不到参数语义 |
| `additionalProperties` | 无字段 | 开放对象无法表达 |
| nested record | 实际已支持（record 嵌 record，`fromType` 递归） | 缺测试用例显式覆盖，回归风险高 |
| 嵌套泛型 `List<List<X>>` | 已支持（递归） | 同上，缺测试 |
| record 内泛型字段 `Map<String,List<X>>` | 抛 `Unsupported` | 复合容器无法表达 |

### 2.3 provider 现状

两个 provider 各自有一份 `toJsonNode(JsonSchema)`，目前只映射 `type` / `properties` / `required` / `items`：

- OpenAI：`helm-provider-openai/src/main/java/io/agent/helm/provider/openai/OpenAiProvider.java:262-277`
- Anthropic：`helm-provider-anthropic/src/main/java/io/agent/helm/provider/anthropic/AnthropicProvider.java:281-296`

两份实现完全相同，且都不感知新字段。本次扩展需统一更新。

### 2.4 现有测试

- `helm-core/src/test/java/io/agent/helm/core/type/TypeDescriptorTest.java` 覆盖了 record / primitive-like / List / 不支持类型 / 防御性拷贝。本组件保留这些用例，新增能力放到 `JsonSchemaContractTest`（test-jar）。
- `helm-core/src/test/java/io/agent/helm/core/tool/ToolDescriptorTest.java` 验证 `Tool.inputSchema()` 间接走 `JsonSchema.from`，需随新能力补 round-trip 用例。

---

## 3. 设计方案

### 3.1 新 record 定义

在原 record 基础上追加四个不可变字段，保持 `Map.copyOf` / `List.copyOf` 防御性拷贝：

```java
package io.agent.helm.core.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        // additionalProperties 可为 null（表示不输出该字段）
    }

    // —— 原有工厂签名保持不变（向后兼容）——
    public static JsonSchema string() {
        return new JsonSchema("string", Map.of(), List.of(), null, null, List.of(), false, null);
    }

    public static JsonSchema integer() {
        return new JsonSchema("integer", Map.of(), List.of(), null, null, List.of(), false, null);
    }

    public static JsonSchema number() {
        return new JsonSchema("number", Map.of(), List.of(), null, null, List.of(), false, null);
    }

    public static JsonSchema bool() {
        return new JsonSchema("boolean", Map.of(), List.of(), null, null, List.of(), false, null);
    }

    public static JsonSchema object(Map<String, JsonSchema> properties, List<String> required) {
        return new JsonSchema("object", Map.copyOf(properties), List.copyOf(required), null,
                null, List.of(), false, null);
    }

    public static JsonSchema array(JsonSchema items) {
        return new JsonSchema("array", Map.of(), List.of(), items, null, List.of(), false, null);
    }

    // —— 新工厂：覆盖新能力 ——
    public static JsonSchema enumeration(List<String> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("enumValues must not be empty");
        }
        return new JsonSchema("string", Map.of(), List.of(), null, null, List.copyOf(values), false, null);
    }

    public static JsonSchema map(JsonSchema valueSchema) {
        Objects.requireNonNull(valueSchema, "valueSchema");
        return new JsonSchema("object", Map.of(), List.of(), null, null, List.of(), false, valueSchema);
    }

    /** 标记任意 schema 为可空，返回新实例（不可变）。 */
    public JsonSchema nullable() {
        return new JsonSchema(type, properties, required, items, description, enumValues, true, additionalProperties);
    }

    /** 附加描述，返回新实例。 */
    public JsonSchema describedAs(String desc) {
        return new JsonSchema(type, properties, required, items, desc, enumValues, nullable, additionalProperties);
    }

    // —— from(TypeDescriptor) -> fromType(Type) 扩展见 3.3 ——
}
```

设计要点：

1. 新字段全部走可空 + 默认值，原 `string()/integer()/.../object(...)/array(...)` 签名不变，已有调用方零修改。
2. `nullable` / `describedAs` 用 wither 风格，避免膨胀 builder；保持 record 不可变。
3. `enumValues` 用 `List.copyOf`，空时归一为 `List.of()`，避免在序列化时输出空数组。
4. `additionalProperties` 用 `JsonSchema`（而非 `Boolean`）：当值为 `map(valueSchema)` 时是正向 schema；当需要表达「禁止额外属性」时另设独立工厂（见 3.4）。

### 3.2 `@SchemaDescription` 注解

字段级 description 的来源。新增到 `core.type` 子包，注解保留策略 `RUNTIME`，目标 `RECORD_COMPONENT`：

```java
package io.agent.helm.core.type;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 附加到 record component 上，作为 JsonSchema.description 的来源。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface SchemaDescription {
    String value();
}
```

`fromType` 在处理 record 时读取该注解；非 record 类型（如直接 `TypeDescriptor.of(String.class)`）description 为 `null`，由调用方通过 `describedAs(...)` 显式补充。

### 3.3 `fromType` 扩展

```java
public static JsonSchema from(TypeDescriptor<?> descriptor) {
    return fromType(descriptor.type());
}

private static JsonSchema fromType(Type type) {
    if (type instanceof Class<?> clazz) {
        if (clazz == String.class) return string();
        if (clazz == int.class || clazz == Integer.class
                || clazz == long.class || clazz == Long.class) return integer();
        if (clazz == double.class || clazz == Double.class
                || clazz == float.class || clazz == Float.class) return number();
        if (clazz == boolean.class || clazz == Boolean.class) return bool();
        if (clazz.isEnum()) return enumSchema(clazz);
        if (clazz.isRecord()) return recordSchema(clazz, /*genericArgs=*/ Map.of());
        // Optional<X> 裸类型（无参数化信息）按可空 string 兜底，避免直接抛错
        if (clazz == Optional.class) return string().nullable();
        throw unsupported(type);
    }

    if (type instanceof ParameterizedType pt) {
        Class<?> raw = (Class<?>) pt.getRawType();
        if (raw == List.class) return array(fromType(pt.getActualTypeArguments()[0]));
        if (raw == Map.class) return mapSchema(pt);
        if (raw == Optional.class) {
            return fromType(pt.getActualTypeArguments()[0]).nullable();
        }
        if (raw.isRecord()) return recordSchema(raw, resolveGenericArgs(pt));
    }
    throw unsupported(type);
}

private static JsonSchema enumSchema(Class<?> enumClass) {
    Object[] constants = enumClass.getEnumConstants();
    List<String> names = new java.util.ArrayList<>(constants.length);
    for (Object c : constants) names.add(((Enum<?>) c).name());
    return enumeration(names);
}

private static JsonSchema mapSchema(ParameterizedType pt) {
    Type keyType = pt.getActualTypeArguments()[0];
    Type valueType = pt.getActualTypeArguments()[1];
    if (!(keyType instanceof Class<?> keyClass) || keyClass != String.class) {
        throw new IllegalArgumentException(
                "Map key must be String, got: " + keyType.getTypeName());
    }
    return map(fromType(valueType));
}

private static JsonSchema recordSchema(Class<?> recordClass, Map<String, Type> genericArgs) {
    Map<String, JsonSchema> properties = new LinkedHashMap<>();
    List<String> required = new java.util.ArrayList<>();
    for (RecordComponent component : recordClass.getRecordComponents()) {
        JsonSchema fieldSchema = fromType(resolve(component.getGenericType(), genericArgs));
        SchemaDescription desc = component.getAnnotation(SchemaDescription.class);
        if (desc != null) fieldSchema = fieldSchema.describedAs(desc.value());
        if (fieldSchema.nullable()) {
            // 可空字段不进 required；其余字段维持原行为（全部 required）
        } else {
            required.add(component.getName());
        }
        properties.put(component.getName(), fieldSchema);
    }
    return object(properties, required);
}

private static Type resolve(Type type, Map<String, Type> genericArgs) {
    // 处理 record 内引用自身泛型参数的情况（如 record Pair<A>(A left, A right)）
    // 简化：仅支持 TypeVariable 直查；ParameterizedType 递归。
    if (type instanceof java.lang.reflect.TypeVariable<?> tv && genericArgs.containsKey(tv.getName())) {
        return genericArgs.get(tv.getName());
    }
    return type;
}

private static Map<String, Type> resolveGenericArgs(ParameterizedType pt) {
    Class<?> raw = (Class<?>) pt.getRawType();
    java.lang.reflect.TypeVariable<?>[] params = raw.getTypeParameters();
    Map<String, Type> args = new LinkedHashMap<>();
    Type[] actual = pt.getActualTypeArguments();
    for (int i = 0; i < params.length; i++) args.put(params[i].getName(), actual[i]);
    return args;
}

private static IllegalArgumentException unsupported(Type type) {
    return new IllegalArgumentException("Unsupported schema type: " + type.getTypeName());
}
```

行为变化对照：

| 输入 | 旧行为 | 新行为 |
| --- | --- | --- |
| `Map<String, Integer>` | 抛 `Unsupported` | `object` + `additionalProperties = integer`，`required = []` |
| `Map<String, List<String>>` | 抛 `Unsupported` | `object` + `additionalProperties = array(string)` |
| `enum Color { RED, GREEN }` | 抛 `Unsupported` | `string` + `enumValues=[RED,GREEN]` |
| `Optional<String>` | 抛 `Unsupported` | `string` + `nullable=true`，不进 `required` |
| `Optional<List<String>>` | 抛 `Unsupported` | `array(string)` + `nullable=true` |
| `record P(@SchemaDescription("name") String name, Optional<Integer> age)` | 抛 `Unsupported`（age） | `object` + `properties={name(string,desc),age(integer,nullable)}` + `required=[name]` |
| `record Nested(Outer.Inner inner)` | 已支持 | 不变；契约测试新增显式用例 |
| `List<List<String>>` | 已支持 | 不变；契约测试新增显式用例 |
| `Map<Integer, String>` | 抛 `Unsupported` | 抛 `IllegalArgumentException("Map key must be String")` |
| `UUID` | 抛 `Unsupported` | 不变（仍抛，fail-fast） |

### 3.4 「禁止额外属性」表达

默认 `additionalProperties = null`，序列化时不输出该字段（provider 默认允许额外属性）。如需关闭，提供独立工厂：

```java
public static JsonSchema sealedObject(Map<String, JsonSchema> properties, List<String> required) {
    return new JsonSchema("object", Map.copyOf(properties), List.copyOf(required), null,
            null, List.of(), false, /*additionalProperties=*/ bool(false) /*sentinel*/);
}
```

> **未决项 A**：是否在 record 上引入 `@SchemaStrict` 注解，自动把 record 转为 `sealedObject`。见第 8 节。

### 3.5 provider schema 映射

OpenAI 与 Anthropic 各自的 `toJsonNode(JsonSchema)` 需扩展。两个 provider 共享同一段映射逻辑（当前是复制粘贴），建议把映射下沉为 `JsonSchema` 上的 `ObjectNode toJsonObject(ObjectMapper)` 方法（core 不依赖 Jackson —— core 已有 `jackson-annotations` 依赖，但**未引入** `jackson-databind`；故该方案需评估，见未决项 B）。当前先在两个 provider 各自更新 `toJsonNode`：

```java
// OpenAiProvider.toJsonNode / AnthropicProvider.toJsonNode 同步更新
private JsonNode toJsonNode(JsonSchema schema) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    // nullable：OpenAI 用 type 数组 ["string","null"]；Anthropic 同样接受
    if (schema.nullable() && !schema.enumValues().isEmpty()) {
        // enum + nullable：用 anyOf 表达更准确，但为保持简单，仍输出 type + enum + nullable hint
        node.put("type", schema.type());
        node.put("nullable", true);
    } else if (schema.nullable()) {
        node.set("type", OBJECT_MAPPER.createArrayNode().add(schema.type()).add("null"));
    } else {
        node.put("type", schema.type());
    }
    if (schema.description() != null && !schema.description().isBlank()) {
        node.put("description", schema.description());
    }
    if (!schema.enumValues().isEmpty()) {
        ArrayNode en = node.putArray("enum");
        schema.enumValues().forEach(en::add);
    }
    if (!schema.properties().isEmpty()) {
        ObjectNode props = node.putObject("properties");
        schema.properties().forEach((k, v) -> props.set(k, toJsonNode(v)));
    }
    if (!schema.required().isEmpty()) {
        ArrayNode req = node.putArray("required");
        schema.required().forEach(req::add);
    }
    if (schema.items() != null) {
        node.set("items", toJsonNode(schema.items()));
    }
    if (schema.additionalProperties() != null) {
        node.set("additionalProperties", toJsonNode(schema.additionalProperties()));
    }
    return node;
}
```

provider 能力差异：

| 字段 | OpenAI function parameters | Anthropic input_schema | 差异处理 |
| --- | --- | --- | --- |
| `type` / `properties` / `required` / `items` | 直接支持 | 直接支持 | 无差异 |
| `description` | 直接支持 | 直接支持 | 无差异 |
| `enum` | 直接支持 | 直接支持 | 无差异 |
| `nullable=true` | 用 `["type","null"]` 表达 | 用 `["type","null"]` 表达 | 一致；OpenAI 也接受 `nullable:true` 但非标准 |
| `additionalProperties` | 支持 | 支持（与 JSON Schema 一致） | 一致；若未来 Anthropic 限流该字段，回退为忽略（log warn，不抛错） |
| `enum + nullable` | 用 `anyOf` 更准 | 同 OpenAI | 当前简化为 `type + enum + nullable:true`，可接受 |

> **未决项 C**：是否在 provider 层引入 `SchemaMappingException`（当 provider 不支持某字段时），还是默默降级。当前选默默降级 + debug 日志，避免 core 改动引发 provider 报错。

---

## 4. 数据流与时序

```text
Application code
  Tool<I,O>.inputType()  →  TypeDescriptor<I>
                              │
                              ▼
  JsonSchema.from(TypeDescriptor)            （helm-core，反射）
      ├── Class<?>：String/Integer/.../enum/record/Optional
      └── ParameterizedType：List<X> / Map<String,X> / Optional<X> / record generics
                              │
                              ▼
  JsonSchema record（不可变，含 description/enumValues/nullable/additionalProperties）
                              │
        ┌─────────────────────┴─────────────────────┐
        ▼                                            ▼
  ToolDescriptor.inputSchema()                engine hardening #2 校验 tool input
        │                                  （结构层面：类型匹配、required 满足、enum 取值）
        ▼                                            │
  ModelRequest.tools()                              │
        │                                            ▼
        ▼                              ToolExecutor 调用 user Tool 前：用 JsonSchema 校验 input
  Provider.stream(ModelRequest)           失败 → ToolExecutionException(code=INVALID_INPUT)
        │
        ├─ OpenAiProvider.toJsonNode(schema)  → function.parameters
        └─ AnthropicProvider.toJsonNode(schema) → input_schema
                                                          │
                                                          ▼
                                              Model 返回 tool_call(input)
                                                          │
                                                          ▼
                                          engine 拿到 input → JsonSchema 再校验 → Tool.execute
```

关键点：

1. `JsonSchema` 在 `Tool.inputSchema()` 处一次性构造，缓存于 `ToolDescriptor`（`ToolDescriptor.from(tool)` 即构造点）。不要在每次 `ModelRequest` 时重反射。
2. provider 端 `toJsonNode` 是无状态纯函数，可在 provider 内缓存（按 `JsonSchema` 实例引用相等）。
3. engine hardening #2 的 tool input 校验**复用** `JsonSchema`，不再引入第二个 schema 描述类型；值层面校验（如 `pattern`、`minimum`）不在本组件范围。
4. `Optional<X>` 反序列化：engine 拿到的 tool input 是 provider 解析后的 `Object`（`Map`/`List`/基本类型），engine 负责把它绑定到 user Tool 的 `I`（record）。`Optional<X>` 字段缺失或为 `null` → `Optional.empty()`；存在 → `Optional.of(value)`。这是 engine 反序列化层职责，本组件只负责 schema 描述。

---

## 5. 安全与边界

### 5.1 默认安全

- `nullable` 默认 `false`，record 字段默认 `required`，避免静默接受缺失入参。
- `additionalProperties` 默认 `null`（不输出），即 provider 默认允许额外属性；这是与现有行为对齐的保守选择。需要严格时显式用 `sealedObject(...)` 或 `@SchemaStrict`。
- `enumValues` 不为空时才输出 `enum`，避免空数组让 provider 误判为「无取值」。

### 5.2 fail-fast

- 未知类型：仍抛 `IllegalArgumentException("Unsupported schema type: " + ...)`（保持现状，`JsonSchema.java:71`）。
- `Map` key 非 `String`：抛 `IllegalArgumentException("Map key must be String: " + ...)`。
- `enumValues` 空数组通过 `enumeration(...)` 工厂：抛 `IllegalArgumentException("enumValues must not be empty")`。
- record component 引用无法解析的泛型（如通配符 `? extends X`）：抛 `Unsupported schema type`。
- 反射访问失败（`RecordComponent.getGenericType` 抛 `GenericSignatureFormatError`）：包装为 `IllegalArgumentException`，不泄漏反射异常。

### 5.3 拒绝危险 schema

- 不允许循环引用：record A 含字段类型为 A 自身（`record Node(Node next)`）。`fromType` 用 `IdentityHashMap<Type, JsonSchema>` 检测循环，抛 `IllegalArgumentException("Recursive schema not supported: " + ...)`。
- 不允许 `Map<String, ?>` 中 `?` 为 `Object` 或 `Object` 自身（`map(Object.class)`）：抛 `IllegalArgumentException("Map value must be concrete type, got Object")`，避免 schema 退化为「任意值」。
- 嵌套深度上限：`fromType` 递归深度超过 32 抛 `IllegalArgumentException("Schema nesting too deep")`，防止恶意构造的超深 record 栈溢出。

### 5.4 依赖守则

- `helm-core` 不引入 `jackson-databind`（只保留 `jackson-annotations`）。schema → JSON 的序列化仍由 provider 自己的 `ObjectMapper` 完成。
- `@SchemaDescription` 注解在 `core.type` 子包，与 `JsonSchema` 同包；不依赖任何外部注解（如 Swagger `@Schema`）。
- 不在 core 引入 `Optional` 之外的新 JDK 依赖。

---

## 6. 测试策略

### 6.1 `JsonSchemaContractTest`（test-jar 基类）

新增到 `helm-core/src/test/java/io/agent/helm/core/type/JsonSchemaContractTest.java`，随 helm-core test-jar 发布（pom.xml 已有 `test-jar` execution，无需改 pom）。

抽象基类提供共享 fixture 与 round-trip 断言；具体测试可在 helm-core 直接继承，未来 provider 也可继承验证 `toJsonNode` 输出。

```java
package io.agent.helm.core.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** JsonSchema 与 TypeDescriptor round-trip 合约。helm-core test-jar 发布。 */
public abstract class JsonSchemaContractTest {

    protected abstract JsonSchema fromTypeChecked(TypeDescriptor<?> descriptor);

    // —— 基础类型 ——
    record Primitives(String s, int i, long l, double d, float f, boolean b) {}

    @Test
    default void primitivesRoundTrip() {
        JsonSchema schema = fromTypeChecked(TypeDescriptor.of(Primitives.class));
        assertThat(schema.type()).isEqualTo("object");
        assertThat(schema.properties()).containsKeys("s", "i", "l", "d", "f", "b");
        assertThat(schema.required()).containsExactly("s", "i", "l", "d", "f", "b");
        assertThat(schema.properties().get("s").type()).isEqualTo("string");
        assertThat(schema.properties().get("i").type()).isEqualTo("integer");
        assertThat(schema.properties().get("d").type()).isEqualTo("number");
        assertThat(schema.properties().get("b").type()).isEqualTo("boolean");
    }

    // —— enum ——
    enum Status { PENDING, RUNNING, DONE }

    @Test
    default void enumRoundTrip() {
        JsonSchema schema = fromTypeChecked(TypeDescriptor.of(Status.class));
        assertThat(schema.type()).isEqualTo("string");
        assertThat(schema.enumValues()).containsExactly("PENDING", "RUNNING", "DONE");
    }

    record WithEnum(String id, Status status) {}

    @Test
    default void recordWithEnumRoundTrip() {
        JsonSchema schema = fromTypeChecked(TypeDescriptor.of(WithEnum.class));
        assertThat(schema.properties().get("status").enumValues())
                .containsExactly("PENDING", "RUNNING", "DONE");
    }

    // —— Map ——
    record WithMap(String id, Map<String, Integer> scores) {}

    @Test
    default void mapRoundTrip() {
        JsonSchema schema = fromTypeChecked(TypeDescriptor.of(WithMap.class));
        JsonSchema scores = schema.properties().get("scores");
        assertThat(scores.type()).isEqualTo("object");
        assertThat(scores.additionalProperties()).isNotNull();
        assertThat(scores.additionalProperties().type()).isEqualTo("integer");
        // required 仍包含 scores（Map 字段本身必填，value 任意）
        assertThat(schema.required()).contains("scores");
    }

    @Test
    default void mapValueNestedGeneric() {
        JsonSchema schema = fromTypeChecked(new TypeDescriptor<Map<String, List<String>>>() {});
        assertThat(schema.type()).isEqualTo("object");
        assertThat(schema.additionalProperties().type()).isEqualTo("array");
        assertThat(schema.additionalProperties().items().type()).isEqualTo("string");
    }

    @Test
    default void nonStringMapKeyFails() {
        assertThatThrownBy(() -> fromTypeChecked(new TypeDescriptor<Map<Integer, String>>() {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Map key must be String");
    }

    // —— Optional / nullable ——
    record WithOptional(String required, Optional<Integer> optional) {}

    @Test
    default void optionalRoundTrip() {
        JsonSchema schema = fromTypeChecked(TypeDescriptor.of(WithOptional.class));
        assertThat(schema.required()).containsExactly("required");
        JsonSchema opt = schema.properties().get("optional");
        assertThat(opt.type()).isEqualTo("integer");
        assertThat(opt.nullable()).isTrue();
    }

    @Test
    default void optionalOfList() {
        JsonSchema schema = fromTypeChecked(new TypeDescriptor<Optional<List<String>>>() {});
        assertThat(schema.type()).isEqualTo("array");
        assertThat(schema.nullable()).isTrue();
        assertThat(schema.items().type()).isEqualTo("string");
    }

    // —— nested record ——
    record Address(String city, String zip) {}
    record Person(String name, Address address) {}

    @Test
    default void nestedRecordRoundTrip() {
        JsonSchema schema = fromTypeChecked(TypeDescriptor.of(Person.class));
        JsonSchema addr = schema.properties().get("address");
        assertThat(addr.type()).isEqualTo("object");
        assertThat(addr.properties()).containsKeys("city", "zip");
        assertThat(addr.required()).containsExactly("city", "zip");
    }

    // —— 嵌套泛型 List<List<X>> ——
    @Test
    default void nestedListRoundTrip() {
        JsonSchema schema = fromTypeChecked(new TypeDescriptor<List<List<String>>>() {});
        assertThat(schema.type()).isEqualTo("array");
        assertThat(schema.items().type()).isEqualTo("array");
        assertThat(schema.items().items().type()).isEqualTo("string");
    }

    // —— description ——
    record Described(@SchemaDescription("user display name") String name, int age) {}

    @Test
    default void descriptionFromAnnotation() {
        JsonSchema schema = fromTypeChecked(TypeDescriptor.of(Described.class));
        assertThat(schema.properties().get("name").description()).isEqualTo("user display name");
        assertThat(schema.properties().get("age").description()).isNull();
    }

    // —— 不可变 + 防御性拷贝 ——
    @Test
    default void defensivelyCopiesMutableArgs() {
        Map<String, JsonSchema> props = new java.util.LinkedHashMap<>();
        List<String> req = new java.util.ArrayList<>();
        props.put("a", JsonSchema.string());
        req.add("a");
        JsonSchema schema = JsonSchema.object(props, req);
        props.put("b", JsonSchema.integer());
        req.add("b");
        assertThat(schema.properties()).containsOnlyKeys("a");
        assertThat(schema.required()).containsExactly("a");
    }

    @Test
    default void enumValuesDefensivelyCopied() {
        List<String> values = new java.util.ArrayList<>(List.of("X", "Y"));
        JsonSchema schema = JsonSchema.enumeration(values);
        values.add("Z");
        assertThat(schema.enumValues()).containsExactly("X", "Y");
    }

    // —— fail-fast ——
    @Test
    default void unsupportedTypeFails() {
        assertThatThrownBy(() -> fromTypeChecked(TypeDescriptor.of(java.util.UUID.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported schema type");
    }

    @Test
    default void emptyEnumFails() {
        assertThatThrownBy(() -> JsonSchema.enumeration(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // —— 循环引用 ——
    // 注意：record Node(Node next) 在编译期合法，本测试用反射构造或单独 fixture
}
```

### 6.2 provider 层 `toJsonNode` 测试

- `OpenAiProvider` / `AnthropicProvider` 现有 `*ProviderContractTest`（`helm-provider-openai/src/test/.../OpenAiProviderContractTest.java` 等）只覆盖流式行为。新增独立单测 `OpenAiSchemaMappingTest` / `AnthropicSchemaMappingTest`，断言 `JsonSchema` 各字段映射到 JSON 节点。
- 共享断言：`description` → `description` 字段；`enumValues` → `enum` 数组；`nullable` → `type` 数组含 `"null"`；`additionalProperties` → `additionalProperties` 节点。

### 6.3 `ToolDescriptorTest` 补强

`helm-core/src/test/java/io/agent/helm/core/tool/ToolDescriptorTest.java` 已有 `Echo`、`String` 用例。补充：

- `Tool<Input, Output>`，`Input` 含 `Map`、`enum`、`Optional` 字段，断言 `ToolDescriptor.from(tool).inputSchema()` 正确反映新能力。

### 6.4 测试覆盖目标

- `JsonSchema`：所有工厂方法 + `fromType` 每条分支 + 失败路径。
- provider `toJsonNode`：每个新字段至少一个用例。
- 不引入新依赖；仅 JUnit 5 + AssertJ（已在 helm-core 测试 scope）。

---

## 7. 验收标准

- [ ] `JsonSchema` record 新增 `description`、`enumValues`、`nullable`、`additionalProperties` 四个不可变字段；compact constructor 完成防御性拷贝。
- [ ] 现有工厂 `string()/integer()/number()/bool()/object(...)/array(...)` 签名与行为不变；`ToolDescriptorTest`、`TypeDescriptorTest` 现有用例零修改通过。
- [ ] 新增工厂 `enumeration(List<String>)`、`map(JsonSchema)`、`nullable()`、`describedAs(String)`、`sealedObject(...)`。
- [ ] `fromType` 支持 `Map<String,X>`、`enum`、`Optional<X>`、嵌套泛型（`List<List<X>>`、record 内泛型）。
- [ ] `@SchemaDescription` 注解在 `core.type`，`fromType` 读取 record component 上的注解并填入 `description`。
- [ ] OpenAI / Anthropic 两个 provider 的 `toJsonNode` 同步更新，输出 `description` / `enum` / `nullable` / `additionalProperties`。
- [ ] `JsonSchemaContractTest` 随 helm-core test-jar 发布，覆盖所有上述 round-trip 用例。
- [ ] 循环引用、`Map` 非 String key、空 `enumValues`、超深嵌套均 fail-fast，错误信息可定位。
- [ ] `mvn verify` 全绿；`helm-core` 无新增生产依赖；`helm-provider-*` 无新增依赖。
- [ ] 与 engine hardening #2 对齐：`JsonSchema` 可被 `ToolExecutor` 用于结构层 tool input 校验（值层校验不在本组件）。

---

## 8. 风险与未决项

| # | 风险 / 未决项 | 影响 | 倾向 |
| --- | --- | --- | --- |
| A | 是否引入 `@SchemaStrict` 把 record 自动转 `sealedObject` | 改变 record 默认行为（默认开 → 默认严），可能破坏现有 tool 与模型行为 | 默认仍开放，`@SchemaStrict` 显式开启 |
| B | `JsonSchema.toJsonNode` 是否下沉到 core | 当前两个 provider 复制粘贴同一段映射；core 引入 `jackson-databind` 才能下沉 | 暂不下沉；先把映射逻辑提取为 provider 内 private static 方法，未来 core 引入 databind 时再统一 |
| C | provider 不支持某字段时策略 | 默默降级 vs 抛 `SchemaMappingException` | 默默降级 + debug log，避免 core 改动导致 provider 报错；只在 `enum + nullable` 等组合字段加显式注释 |
| D | `Optional<X>` 在 record 中是否允许 `Optional.empty()` 等价于字段缺失 | 反序列化语义由 engine 决定，本组件只描述 schema | 描述层 `nullable=true` + 不进 `required`；engine 反序列化层独立处理 |
| E | 是否支持 `record` 上的 `@SchemaDescription`（类级别 description） | Tool 已有 `Tool.description()`，类级别 description 来源可能重复 | 仅支持 component 级；类级别 description 仍由 `Tool.description()` 提供 |
| F | `Map<String, X>` 中 `X` 为 `Object` 时 | schema 退化为「任意值」，模型可能输出任意结构 | fail-fast 拒绝；如需任意值，显式用 `JsonSchema.object(Map.of(), List.of())` 表示 |
| G | 循环引用检测 | `record Node(Node next)` 在 Java 合法，但 JSON Schema 无限递归 | fail-fast，错误信息指明 record 名与字段 |
| H | `enum` 在 record 字段上的 `description` 来源 | `@SchemaDescription` 描述字段还是 enum 本身 | 描述字段（即「该字段允许的取值集合」），enum 类型自身的描述由 `enumeration(...).describedAs(...)` 显式补充 |

---

## 9. 与其他组件的关系

| 组件 | 关系 | 说明 |
| --- | --- | --- |
| **02 Engine hardening** | 强依赖（被依赖） | engine hardening #2 的 tool input 校验复用 `JsonSchema`；本组件必须先于或同 PR 落地 #2 的校验部分 |
| **01 Streaming API** | 无直接关系 | streaming 不涉及 schema |
| **05 Authorizer / SecurityContext** | 无直接关系 | schema 不携带授权信息 |
| **M4 Real providers** | 已落地，本组件扩展其 `toJsonNode` | OpenAI / Anthropic provider 同步更新 |
| **M5 Skills and Sandbox** | 弱关系 | Skill 不直接消费 `JsonSchema`；若未来 skill 描述需要 schema，可复用 |
| **M6 HTTP / Client SDK** | 间接 | HTTP 路由暴露 tool 元数据时（如 `GET /tools`）可能输出 `JsonSchema`，需走 provider 无关的 JSON 序列化（本组件未定，留给 #6 设计） |
| **M2 RuntimeStore / 持久化** | 无关系 | schema 不持久化 |
| **API governance（#11）** | 对齐 | `@SchemaDescription` 是 `core.type` 子包的新 public 注解，需在 API governance 中登记为 public API |

---

**作者备注**：本组件是 M3 engine hardening 的前置切片，落地后即可解锁 #2 的 tool input 校验。建议实施顺序：1）`JsonSchema` record 扩展 + `@SchemaDescription` + `fromType` 扩展 + `JsonSchemaContractTest`；2）两个 provider `toJsonNode` 更新；3）与 engine hardening #2 联调 tool input 校验。
