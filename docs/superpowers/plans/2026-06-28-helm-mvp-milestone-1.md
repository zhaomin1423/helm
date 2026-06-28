# Helm MVP Milestone 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Build Helm Milestone 1: a Maven Java 21 multi-module core runtime with `helm-core`, `helm-agent-engine`, `helm-runtime`, fake provider, in-memory store, and tests proving agent prompt and workflow execution.

**Architecture:** The implementation is core-first: `helm-core` defines stable public API and SPI, `helm-agent-engine` executes prepared model/tool turns without persistence or discovery, and `helm-runtime` owns registries, sessions, operations, workflows, store, and event bus. The package namespace is `io.agent.helm`, and no core module depends on Spring, Servlet, CLI, provider SDKs, or JDBC.

**Tech Stack:** Java 21, Maven multi-module, JUnit 5, AssertJ, Jackson annotations only where needed for future serialization compatibility.

---

## Scope

This plan implements Milestone 1 only. The approved spec covers the full MVP in phases, but HTTP, CLI, Spring Boot, real providers, local sandbox, JDBC, and logging observers each need separate plans after Milestone 1 is green.

## File structure

Create this structure:

```text
pom.xml
helm-core/pom.xml
helm-core/src/main/java/io/agent/helm/core/...
helm-core/src/test/java/io/agent/helm/core/...
helm-agent-engine/pom.xml
helm-agent-engine/src/main/java/io/agent/helm/engine/...
helm-agent-engine/src/test/java/io/agent/helm/engine/...
helm-runtime/pom.xml
helm-runtime/src/main/java/io/agent/helm/runtime/...
helm-runtime/src/test/java/io/agent/helm/runtime/...
docs/examples/milestone-1-agent-workflow.md
```

Key file responsibilities:

| File area | Responsibility |
| --- | --- |
| `helm-core/.../error` | Structured `HelmException` hierarchy and stable error codes. |
| `helm-core/.../type` | `TypeDescriptor<T>` and minimal `JsonSchema` generation for records, primitives, lists, and maps. |
| `helm-core/.../model` | Model provider SPI, `ModelRef`, stream events, request/response value objects. |
| `helm-core/.../message` | `HelmMessage` and content blocks. |
| `helm-core/.../agent`, `workflow`, `tool`, `sandbox`, `store`, `event` | Public API and SPI contracts. |
| `helm-agent-engine/...` | Prepared engine request execution, stream normalization, tool orchestration, stop conditions. |
| `helm-runtime/...` | Registries, runtime API, harness/session lifecycle, operation/workflow records, fake provider, in-memory store. |

## Task 1: Maven skeleton

**Files:**
- Create: `pom.xml`
- Create: `helm-core/pom.xml`
- Create: `helm-agent-engine/pom.xml`
- Create: `helm-runtime/pom.xml`

- [x] **Step 1: Write parent Maven build**

Create `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>io.agent</groupId>
  <artifactId>helm</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <name>Helm</name>
  <description>Java Agent Harness Framework</description>

  <modules>
    <module>helm-core</module>
    <module>helm-agent-engine</module>
    <module>helm-runtime</module>
  </modules>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <junit.version>5.10.3</junit.version>
    <assertj.version>3.26.3</assertj.version>
    <jackson.version>2.17.2</jackson.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>${junit.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>${assertj.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-annotations</artifactId>
        <version>${jackson.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.13.0</version>
          <configuration>
            <release>${maven.compiler.release}</release>
          </configuration>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>3.3.1</version>
          <configuration>
            <useModulePath>false</useModulePath>
          </configuration>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

- [x] **Step 2: Write module POMs**

Create `helm-core/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.agent</groupId>
    <artifactId>helm</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>helm-core</artifactId>

  <dependencies>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-annotations</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

Create `helm-agent-engine/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.agent</groupId>
    <artifactId>helm</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>helm-agent-engine</artifactId>

  <dependencies>
    <dependency>
      <groupId>io.agent</groupId>
      <artifactId>helm-core</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

Create `helm-runtime/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.agent</groupId>
    <artifactId>helm</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>helm-runtime</artifactId>

  <dependencies>
    <dependency>
      <groupId>io.agent</groupId>
      <artifactId>helm-core</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>io.agent</groupId>
      <artifactId>helm-agent-engine</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [x] **Step 3: Run the initial build**

Run:

```bash
mvn test
```

Expected: Maven succeeds with three empty modules and exit code 0.

- [x] **Step 4: Commit**

```bash
git add pom.xml helm-core/pom.xml helm-agent-engine/pom.xml helm-runtime/pom.xml
git commit -m "build: add Maven multi-module skeleton" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 2: Core errors, types, schema, messages, and model SPI

**Files:**
- Create: `helm-core/src/test/java/io/agent/helm/core/type/TypeDescriptorTest.java`
- Create: `helm-core/src/test/java/io/agent/helm/core/error/HelmExceptionTest.java`
- Create: `helm-core/src/test/java/io/agent/helm/core/model/ModelRefTest.java`
- Create: `helm-core/src/main/java/io/agent/helm/core/error/*.java`
- Create: `helm-core/src/main/java/io/agent/helm/core/type/*.java`
- Create: `helm-core/src/main/java/io/agent/helm/core/message/*.java`
- Create: `helm-core/src/main/java/io/agent/helm/core/model/*.java`

- [x] **Step 1: Write failing core tests**

Create `TypeDescriptorTest.java`:

```java
package io.agent.helm.core.type;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
}
```

Create `HelmExceptionTest.java`:

```java
package io.agent.helm.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class HelmExceptionTest {
    @Test
    void exposesStableCodeAndSeparatesSafeAndDeveloperDetails() {
        HelmException error = new ValidationException(
            "Invalid input",
            Map.of("field", "name"),
            Map.of("rawValue", "")
        );

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.details()).containsEntry("field", "name");
        assertThat(error.developerDetails()).containsEntry("rawValue", "");
    }
}
```

Create `ModelRefTest.java`:

```java
package io.agent.helm.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class ModelRefTest {
    @Test
    void parsesProviderAndModel() {
        ModelRef ref = ModelRef.parse("openai/gpt-4.1");

        assertThat(ref.providerId()).isEqualTo("openai");
        assertThat(ref.modelId()).isEqualTo("gpt-4.1");
        assertThat(ref.value()).isEqualTo("openai/gpt-4.1");
    }

    @Test
    void rejectsAmbiguousModelStrings() {
        assertThatThrownBy(() -> ModelRef.parse("gpt-4.1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("provider/model");
    }
}
```

- [x] **Step 2: Run tests and verify they fail**

Run:

```bash
mvn -pl helm-core test
```

Expected: FAIL because `TypeDescriptor`, `JsonSchema`, `ValidationException`, and `ModelRef` do not exist yet.

- [x] **Step 3: Add structured errors**

Create these files in `helm-core/src/main/java/io/agent/helm/core/error/`:

```java
package io.agent.helm.core.error;

import java.util.Map;

public abstract class HelmException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;
    private final Map<String, Object> developerDetails;

    protected HelmException(String code, String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
        this.developerDetails = Map.copyOf(developerDetails);
    }

    public String code() { return code; }
    public Map<String, Object> details() { return details; }
    public Map<String, Object> developerDetails() { return developerDetails; }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class ValidationException extends HelmException {
    public ValidationException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("VALIDATION_FAILED", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class AgentNotFoundException extends HelmException {
    public AgentNotFoundException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("AGENT_NOT_FOUND", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class WorkflowNotFoundException extends HelmException {
    public WorkflowNotFoundException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("WORKFLOW_NOT_FOUND", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class ProviderNotFoundException extends HelmException {
    public ProviderNotFoundException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("PROVIDER_NOT_FOUND", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class ToolExecutionException extends HelmException {
    public ToolExecutionException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("TOOL_EXECUTION_FAILED", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class SandboxException extends HelmException {
    public SandboxException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("SANDBOX_ERROR", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class PersistenceException extends HelmException {
    public PersistenceException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("PERSISTENCE_ERROR", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class SessionBusyException extends HelmException {
    public SessionBusyException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("SESSION_BUSY", message, details, developerDetails);
    }
}
```

```java
package io.agent.helm.core.error;

import java.util.Map;

public final class ContextOverflowException extends HelmException {
    public ContextOverflowException(String message, Map<String, Object> details, Map<String, Object> developerDetails) {
        super("CONTEXT_OVERFLOW", message, details, developerDetails);
    }
}
```

- [x] **Step 4: Add type descriptor and schema**

Create `TypeDescriptor.java`:

```java
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

    public Type type() { return type; }
    public String typeName() { return type.getTypeName(); }
}
```

Create `JsonSchema.java`:

```java
package io.agent.helm.core.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record JsonSchema(String type, Map<String, JsonSchema> properties, List<String> required, JsonSchema items) {
    public static JsonSchema string() { return new JsonSchema("string", Map.of(), List.of(), null); }
    public static JsonSchema integer() { return new JsonSchema("integer", Map.of(), List.of(), null); }
    public static JsonSchema number() { return new JsonSchema("number", Map.of(), List.of(), null); }
    public static JsonSchema bool() { return new JsonSchema("boolean", Map.of(), List.of(), null); }
    public static JsonSchema object(Map<String, JsonSchema> properties, List<String> required) {
        return new JsonSchema("object", Map.copyOf(properties), List.copyOf(required), null);
    }
    public static JsonSchema array(JsonSchema items) { return new JsonSchema("array", Map.of(), List.of(), items); }

    public static JsonSchema from(TypeDescriptor<?> descriptor) {
        return fromType(descriptor.type());
    }

    private static JsonSchema fromType(Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz == String.class) return string();
            if (clazz == int.class || clazz == Integer.class || clazz == long.class || clazz == Long.class) return integer();
            if (clazz == double.class || clazz == Double.class || clazz == float.class || clazz == Float.class) return number();
            if (clazz == boolean.class || clazz == Boolean.class) return bool();
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
        return object(Map.of(), List.of());
    }
}
```

- [x] **Step 5: Add messages and model SPI**

Create `Role.java`, `ContentBlock.java`, and `HelmMessage.java`:

```java
package io.agent.helm.core.message;

public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
```

```java
package io.agent.helm.core.message;

public sealed interface ContentBlock permits TextBlock, ToolCallBlock, ToolResultBlock {}
```

```java
package io.agent.helm.core.message;

public record TextBlock(String text) implements ContentBlock {}
```

```java
package io.agent.helm.core.message;

public record ToolCallBlock(String id, String name, Object input) implements ContentBlock {}
```

```java
package io.agent.helm.core.message;

public record ToolResultBlock(String toolCallId, Object output, boolean error) implements ContentBlock {}
```

```java
package io.agent.helm.core.message;

import java.util.List;

public record HelmMessage(Role role, List<ContentBlock> content) {
    public HelmMessage {
        content = List.copyOf(content);
    }

    public static HelmMessage user(String text) {
        return new HelmMessage(Role.USER, List.of(new TextBlock(text)));
    }

    public static HelmMessage assistant(String text) {
        return new HelmMessage(Role.ASSISTANT, List.of(new TextBlock(text)));
    }
}
```

Create `ModelRef.java`, `ModelProvider.java`, `ModelRequest.java`, `ModelStreamEvent.java`, and `TokenUsage.java`:

```java
package io.agent.helm.core.model;

public record ModelRef(String providerId, String modelId) {
    public static ModelRef parse(String value) {
        String[] parts = value.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Model reference must use provider/model format");
        }
        return new ModelRef(parts[0], parts[1]);
    }

    public String value() {
        return providerId + "/" + modelId;
    }
}
```

```java
package io.agent.helm.core.model;

import io.agent.helm.core.error.HelmException;
import java.util.concurrent.Flow;

public interface ModelProvider {
    boolean supports(ModelRef model);
    Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) throws HelmException;
}
```

```java
package io.agent.helm.core.model;

import io.agent.helm.core.message.HelmMessage;
import java.time.Duration;
import java.util.List;

public record ModelRequest(ModelRef model, String instructions, List<HelmMessage> messages, Duration timeout) {
    public ModelRequest {
        messages = List.copyOf(messages);
    }
}
```

```java
package io.agent.helm.core.model;

public sealed interface ModelStreamEvent permits ModelStreamEvent.ContentDelta, ModelStreamEvent.ToolCallRequested, ModelStreamEvent.Completed {
    record ContentDelta(String text) implements ModelStreamEvent {}
    record ToolCallRequested(String id, String name, Object input) implements ModelStreamEvent {}
    record Completed(TokenUsage usage) implements ModelStreamEvent {}
}
```

```java
package io.agent.helm.core.model;

public record TokenUsage(long inputTokens, long outputTokens) {}
```

- [x] **Step 6: Run core tests**

Run:

```bash
mvn -pl helm-core test
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add helm-core
git commit -m "feat: add core error type and model contracts" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 3: Core public API and store contracts

**Files:**
- Create: `helm-core/src/test/java/io/agent/helm/core/agent/AgentConfigTest.java`
- Create: `helm-core/src/main/java/io/agent/helm/core/agent/*.java`
- Create: `helm-core/src/main/java/io/agent/helm/core/workflow/*.java`
- Create: `helm-core/src/main/java/io/agent/helm/core/tool/*.java`
- Create: `helm-core/src/main/java/io/agent/helm/core/sandbox/*.java`
- Create: `helm-core/src/main/java/io/agent/helm/core/store/*.java`
- Create: `helm-core/src/main/java/io/agent/helm/core/event/*.java`

- [x] **Step 1: Write failing API tests**

Create `AgentConfigTest.java`:

```java
package io.agent.helm.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.model.ModelRef;
import org.junit.jupiter.api.Test;

final class AgentConfigTest {
    @Test
    void buildsAgentConfigWithModelAndInstructions() {
        AgentConfig config = AgentConfig.builder()
            .model("openai/gpt-4.1")
            .instructions("You are helpful.")
            .build();

        assertThat(config.model()).isEqualTo(ModelRef.parse("openai/gpt-4.1"));
        assertThat(config.instructions()).isEqualTo("You are helpful.");
        assertThat(config.tools()).isEmpty();
    }
}
```

- [x] **Step 2: Run test and verify it fails**

Run:

```bash
mvn -pl helm-core test
```

Expected: FAIL because `AgentConfig` does not exist.

- [x] **Step 3: Add agent and tool APIs**

Create `AgentDefinition.java`, `AgentContext.java`, `AgentConfig.java`, `AgentSessionApi.java`, `AgentHarnessApi.java`, `PromptResult.java`, `Tool.java`, `ToolContext.java`, and `ToolResult.java`:

```java
package io.agent.helm.core.agent;

public interface AgentDefinition {
    String name();
    AgentConfig configure(AgentContext context);
}
```

```java
package io.agent.helm.core.agent;

public record AgentContext(String agentName, String instanceId) {}
```

```java
package io.agent.helm.core.agent;

import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;

public record AgentConfig(ModelRef model, String instructions, List<Tool<?, ?>> tools, Sandbox sandbox) {
    public AgentConfig {
        tools = List.copyOf(tools);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private ModelRef model;
        private String instructions = "";
        private final List<Tool<?, ?>> tools = new ArrayList<>();
        private Sandbox sandbox;

        public Builder model(String model) { this.model = ModelRef.parse(model); return this; }
        public Builder instructions(String instructions) { this.instructions = instructions; return this; }
        public Builder tool(Tool<?, ?> tool) { this.tools.add(tool); return this; }
        public Builder sandbox(Sandbox sandbox) { this.sandbox = sandbox; return this; }
        public AgentConfig build() { return new AgentConfig(model, instructions, tools, sandbox); }
    }
}
```

```java
package io.agent.helm.core.agent;

public interface AgentSessionApi {
    PromptResult prompt(String text);
}
```

```java
package io.agent.helm.core.agent;

public interface AgentHarnessApi {
    AgentSessionApi session(String sessionName);
}
```

```java
package io.agent.helm.core.agent;

public record PromptResult(String operationId, String text) {}
```

```java
package io.agent.helm.core.tool;

import io.agent.helm.core.type.JsonSchema;
import io.agent.helm.core.type.TypeDescriptor;

public interface Tool<I, O> {
    String name();
    TypeDescriptor<I> inputType();
    TypeDescriptor<O> outputType();
    default JsonSchema inputSchema() { return JsonSchema.from(inputType()); }
    O execute(ToolContext context, I input) throws Exception;
}
```

```java
package io.agent.helm.core.tool;

public record ToolContext(String operationId) {}
```

```java
package io.agent.helm.core.tool;

public record ToolResult(String toolCallId, Object output, boolean error) {}
```

- [x] **Step 4: Add workflow, sandbox, event, and store contracts**

Create the workflow API:

```java
package io.agent.helm.core.workflow;

import io.agent.helm.core.type.TypeDescriptor;

public interface WorkflowDefinition<I, O> {
    String name();
    WorkflowConfig config();
    TypeDescriptor<I> inputType();
    TypeDescriptor<O> outputType();
    O run(WorkflowContext<I> context) throws Exception;
}
```

```java
package io.agent.helm.core.workflow;

import io.agent.helm.core.agent.AgentDefinition;

public record WorkflowConfig(AgentDefinition agent) {
    public static WorkflowConfig of(AgentDefinition agent) {
        return new WorkflowConfig(agent);
    }
}
```

```java
package io.agent.helm.core.workflow;

import io.agent.helm.core.agent.AgentHarnessApi;

public interface WorkflowContext<I> {
    I input();
    AgentHarnessApi harness();
}
```

Create the sandbox SPI:

```java
package io.agent.helm.core.sandbox;

public interface Sandbox {
    SandboxFileSystem fs();
    SandboxShell shell();
}
```

```java
package io.agent.helm.core.sandbox;

public interface SandboxFileSystem {
    String readText(String path);
    void writeText(String path, String content);
}
```

```java
package io.agent.helm.core.sandbox;

public interface SandboxShell {
    SandboxCommandResult execute(SandboxCommand command);
}
```

```java
package io.agent.helm.core.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record SandboxCommand(List<String> argv, Duration timeout, Map<String, String> environment) {}
```

```java
package io.agent.helm.core.sandbox;

public record SandboxCommandResult(int exitCode, String stdout, String stderr) {}
```

Create runtime event and store contracts:

```java
package io.agent.helm.core.event;

import java.time.Instant;
import java.util.Map;

public record RuntimeEventRecord(String id, String operationId, String workflowRunId, long sequence, String type, Map<String, Object> payload, Instant createdAt) {}
```

```java
package io.agent.helm.core.store;

import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.message.HelmMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RuntimeStore {
    Optional<AgentSessionState> loadSession(String sessionId);
    void saveSession(AgentSessionState session);
    void saveOperation(OperationRecord operation);
    Optional<OperationRecord> loadOperation(String operationId);
    void saveWorkflowRun(WorkflowRunRecord run);
    Optional<WorkflowRunRecord> loadWorkflowRun(String runId);
    void appendEvent(RuntimeEventRecord event);
    List<RuntimeEventRecord> eventsForOperation(String operationId);
    List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId);
}
```

```java
package io.agent.helm.core.store;

import io.agent.helm.core.message.HelmMessage;
import java.time.Instant;
import java.util.List;

public record AgentSessionState(String id, String agentName, String instanceId, String sessionName, long version, List<HelmMessage> messages, Instant createdAt, Instant updatedAt) {
    public AgentSessionState {
        messages = List.copyOf(messages);
    }
}
```

```java
package io.agent.helm.core.store;

import java.time.Instant;
import java.util.Map;

public record OperationRecord(String id, String sessionId, String type, String status, Object input, Object output, Map<String, Object> error, Instant createdAt, Instant completedAt) {}
```

```java
package io.agent.helm.core.store;

import java.time.Instant;
import java.util.Map;

public record WorkflowRunRecord(String id, String workflowName, String status, Object input, Object output, Map<String, Object> error, Instant createdAt, Instant completedAt) {}
```

- [x] **Step 5: Run core tests**

Run:

```bash
mvn -pl helm-core test
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add helm-core
git commit -m "feat: add core public API contracts" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 4: Agent engine terminal and tool-call loop

**Files:**
- Create: `helm-agent-engine/src/test/java/io/agent/helm/engine/AgentEngineTest.java`
- Create: `helm-agent-engine/src/main/java/io/agent/helm/engine/*.java`

- [x] **Step 1: Write failing engine tests**

Create `AgentEngineTest.java`:

```java
package io.agent.helm.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import org.junit.jupiter.api.Test;

final class AgentEngineTest {
    @Test
    void returnsTerminalAssistantText() {
        AgentEngine engine = new AgentEngine();
        AgentEngineResult result = engine.run(new AgentEngineRequest(
            ModelRef.parse("fake/test"),
            "Be helpful.",
            List.of(HelmMessage.user("hello")),
            new ScriptedProvider(List.of(new ModelStreamEvent.ContentDelta("hi"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)))),
            ToolExecutor.none(),
            Duration.ofSeconds(5),
            4
        ));

        assertThat(result.text()).isEqualTo("hi");
        assertThat(result.messages()).last().satisfies(message -> assertThat(message).isEqualTo(HelmMessage.assistant("hi")));
    }

    @Test
    void executesToolCallBeforeFinalAssistantText() {
        ScriptedProvider provider = new ScriptedProvider(
            List.of(new ModelStreamEvent.ToolCallRequested("call_1", "echo", "input"), new ModelStreamEvent.Completed(new TokenUsage(1, 0))),
            List.of(new ModelStreamEvent.ContentDelta("tool said output"), new ModelStreamEvent.Completed(new TokenUsage(1, 3)))
        );
        ToolExecutor executor = (operationId, name, input) -> name.equals("echo") ? "output" : "unexpected";

        AgentEngineResult result = new AgentEngine().run(new AgentEngineRequest(
            ModelRef.parse("fake/test"),
            "Use tools.",
            List.of(HelmMessage.user("run echo")),
            provider,
            executor,
            Duration.ofSeconds(5),
            4
        ));

        assertThat(result.text()).isEqualTo("tool said output");
        assertThat(provider.calls()).isEqualTo(2);
    }

    private static final class ScriptedProvider implements ModelProvider {
        private final List<List<ModelStreamEvent>> scripts;
        private int calls;

        @SafeVarargs
        private ScriptedProvider(List<ModelStreamEvent>... scripts) {
            this.scripts = List.of(scripts);
        }

        @Override
        public boolean supports(ModelRef model) { return true; }

        @Override
        public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
            SubmissionPublisher<ModelStreamEvent> publisher = new SubmissionPublisher<>();
            List<ModelStreamEvent> script = scripts.get(calls++);
            Thread.startVirtualThread(() -> {
                script.forEach(publisher::submit);
                publisher.close();
            });
            return publisher;
        }

        private int calls() { return calls; }
    }
}
```

- [x] **Step 2: Run test and verify it fails**

Run:

```bash
mvn -pl helm-agent-engine -am test
```

Expected: FAIL because engine classes do not exist.

- [x] **Step 3: Add engine request/result and executor**

Create `AgentEngineRequest.java`, `AgentEngineResult.java`, and `ToolExecutor.java`:

```java
package io.agent.helm.engine;

import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import java.time.Duration;
import java.util.List;

public record AgentEngineRequest(ModelRef model, String instructions, List<HelmMessage> messages, ModelProvider provider, ToolExecutor toolExecutor, Duration timeout, int maxTurns) {
    public AgentEngineRequest {
        messages = List.copyOf(messages);
    }
}
```

```java
package io.agent.helm.engine;

import io.agent.helm.core.message.HelmMessage;
import java.util.List;

public record AgentEngineResult(String text, List<HelmMessage> messages) {
    public AgentEngineResult {
        messages = List.copyOf(messages);
    }
}
```

```java
package io.agent.helm.engine;

@FunctionalInterface
public interface ToolExecutor {
    Object execute(String operationId, String name, Object input);

    static ToolExecutor none() {
        return (operationId, name, input) -> {
            throw new IllegalStateException("No tool executor registered");
        };
    }
}
```

- [x] **Step 4: Add stream collection and engine loop**

Create `TurnResult.java`, `TurnRunner.java`, and `AgentEngine.java`:

```java
package io.agent.helm.engine;

import io.agent.helm.core.model.ModelStreamEvent;
import java.util.List;

record TurnResult(String text, List<ModelStreamEvent.ToolCallRequested> toolCalls) {}
```

```java
package io.agent.helm.engine;

import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

final class TurnRunner {
    TurnResult run(ModelProvider provider, ModelRequest request) {
        StringBuilder text = new StringBuilder();
        List<ModelStreamEvent.ToolCallRequested> toolCalls = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        provider.stream(request).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ModelStreamEvent event) {
                if (event instanceof ModelStreamEvent.ContentDelta delta) text.append(delta.text());
                if (event instanceof ModelStreamEvent.ToolCallRequested toolCall) toolCalls.add(toolCall);
            }
            @Override public void onError(Throwable throwable) { done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });

        try {
            if (!done.await(request.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Model stream timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for model stream", e);
        }

        return new TurnResult(text.toString(), List.copyOf(toolCalls));
    }
}
```

```java
package io.agent.helm.engine;

import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.message.ToolCallBlock;
import io.agent.helm.core.message.ToolResultBlock;
import io.agent.helm.core.model.ModelRequest;
import java.util.ArrayList;
import java.util.List;

public final class AgentEngine {
    private final TurnRunner turnRunner = new TurnRunner();

    public AgentEngineResult run(AgentEngineRequest request) {
        List<HelmMessage> messages = new ArrayList<>(request.messages());
        for (int turn = 0; turn < request.maxTurns(); turn++) {
            TurnResult result = turnRunner.run(request.provider(), new ModelRequest(request.model(), request.instructions(), messages, request.timeout()));
            if (result.toolCalls().isEmpty()) {
                HelmMessage assistant = HelmMessage.assistant(result.text());
                messages.add(assistant);
                return new AgentEngineResult(result.text(), messages);
            }
            for (var toolCall : result.toolCalls()) {
                messages.add(new HelmMessage(io.agent.helm.core.message.Role.ASSISTANT, List.of(new ToolCallBlock(toolCall.id(), toolCall.name(), toolCall.input()))));
                Object output = request.toolExecutor().execute("engine", toolCall.name(), toolCall.input());
                messages.add(new HelmMessage(io.agent.helm.core.message.Role.TOOL, List.of(new ToolResultBlock(toolCall.id(), output, false))));
            }
        }
        throw new IllegalStateException("Agent loop exceeded max turns");
    }
}
```

- [x] **Step 5: Run engine tests**

Run:

```bash
mvn -pl helm-agent-engine -am test
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add helm-agent-engine
git commit -m "feat: add agent engine loop" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 5: Runtime store, fake provider, prompt, session, and workflow

**Files:**
- Create: `helm-runtime/src/test/java/io/agent/helm/runtime/AgentRuntimeTest.java`
- Create: `helm-runtime/src/test/java/io/agent/helm/runtime/WorkflowRuntimeTest.java`
- Create: `helm-runtime/src/main/java/io/agent/helm/runtime/*.java`

- [x] **Step 1: Write failing runtime tests**

Create `AgentRuntimeTest.java`:

```java
package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.error.SessionBusyException;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.tool.Tool;
import io.agent.helm.core.tool.ToolContext;
import io.agent.helm.core.type.TypeDescriptor;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class AgentRuntimeTest {
    @Test
    void promptReturnsFakeProviderTextAndPersistsSession() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(new ModelStreamEvent.ContentDelta("hello"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRuntime runtime = AgentRuntime.builder()
            .agent(new AssistantAgent())
            .provider(provider)
            .store(store)
            .build();

        PromptResult result = runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Hi"));

        assertThat(result.text()).isEqualTo("hello");
        assertThat(store.loadSession("assistant:instance-1:default")).isPresent();
        assertThat(store.loadOperation(result.operationId())).isPresent();
    }

    @Test
    void promptExecutesRegisteredTool() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(new ModelStreamEvent.ToolCallRequested("call_1", "echo", "input"), new ModelStreamEvent.Completed(new TokenUsage(1, 0)));
        provider.enqueue(new ModelStreamEvent.ContentDelta("tool said output"), new ModelStreamEvent.Completed(new TokenUsage(1, 3)));
        AgentRuntime runtime = AgentRuntime.builder()
            .agent(new ToolAgent())
            .provider(provider)
            .store(new InMemoryRuntimeStore())
            .build();

        PromptResult result = runtime.prompt(new AgentPromptRequest("tool-agent", "instance-1", "default", "Use the tool"));

        assertThat(result.text()).isEqualTo("tool said output");
    }

    @Test
    void rejectsConcurrentPromptForSameSession() throws Exception {
        BlockingProvider provider = new BlockingProvider("fake");
        AgentRuntime runtime = AgentRuntime.builder()
            .agent(new AssistantAgent())
            .provider(provider)
            .store(new InMemoryRuntimeStore())
            .build();

        Thread first = Thread.startVirtualThread(() -> runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Hi")));
        provider.awaitRequest();

        assertThatThrownBy(() -> runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Again")))
            .isInstanceOf(SessionBusyException.class)
            .hasMessageContaining("Session is busy");

        provider.complete();
        first.join();
    }

    private static final class AssistantAgent implements AgentDefinition {
        @Override public String name() { return "assistant"; }
        @Override public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder().model("fake/test").instructions("You are helpful.").build();
        }
    }

    private static final class ToolAgent implements AgentDefinition {
        @Override public String name() { return "tool-agent"; }
        @Override public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder().model("fake/test").instructions("Use tools.").tool(new EchoTool()).build();
        }
    }

    private static final class EchoTool implements Tool<String, String> {
        @Override public String name() { return "echo"; }
        @Override public TypeDescriptor<String> inputType() { return TypeDescriptor.of(String.class); }
        @Override public TypeDescriptor<String> outputType() { return TypeDescriptor.of(String.class); }
        @Override public String execute(ToolContext context, String input) { return "output"; }
    }

    private static final class BlockingProvider implements io.agent.helm.core.model.ModelProvider {
        private final String providerId;
        private final CountDownLatch requested = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingProvider(String providerId) {
            this.providerId = providerId;
        }

        @Override public boolean supports(io.agent.helm.core.model.ModelRef model) {
            return providerId.equals(model.providerId());
        }

        @Override public Flow.Publisher<ModelStreamEvent> stream(io.agent.helm.core.model.ModelRequest request) {
            SubmissionPublisher<ModelStreamEvent> publisher = new SubmissionPublisher<>();
            Thread.startVirtualThread(() -> {
                requested.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                publisher.submit(new ModelStreamEvent.ContentDelta("done"));
                publisher.submit(new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
                publisher.close();
            });
            return publisher;
        }

        private void awaitRequest() throws InterruptedException {
            requested.await();
        }

        private void complete() {
            release.countDown();
        }
    }
}
```

Create `WorkflowRuntimeTest.java`:

```java
package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.model.ModelStreamEvent;
import io.agent.helm.core.model.TokenUsage;
import io.agent.helm.core.type.TypeDescriptor;
import io.agent.helm.core.workflow.WorkflowConfig;
import io.agent.helm.core.workflow.WorkflowContext;
import io.agent.helm.core.workflow.WorkflowDefinition;
import org.junit.jupiter.api.Test;

final class WorkflowRuntimeTest {
    record Input(String text) {}
    record Output(String text) {}

    @Test
    void invokesWorkflowAndStoresRun() {
        FakeProvider provider = new FakeProvider("fake");
        provider.enqueue(new ModelStreamEvent.ContentDelta("summary"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        WorkflowRuntime runtime = WorkflowRuntime.builder()
            .workflow(new SummarizeWorkflow())
            .provider(provider)
            .store(store)
            .build();

        WorkflowRunHandle<Output> handle = runtime.invoke(new WorkflowInvokeRequest<>("summarize", new Input("long text")));

        assertThat(handle.result().text()).isEqualTo("summary");
        assertThat(store.loadWorkflowRun(handle.runId())).isPresent();
    }

    private static final class Agent implements AgentDefinition {
        @Override public String name() { return "summarizer"; }
        @Override public AgentConfig configure(AgentContext context) {
            return AgentConfig.builder().model("fake/test").instructions("Summarize.").build();
        }
    }

    private static final class SummarizeWorkflow implements WorkflowDefinition<Input, Output> {
        @Override public String name() { return "summarize"; }
        @Override public WorkflowConfig config() { return WorkflowConfig.of(new Agent()); }
        @Override public TypeDescriptor<Input> inputType() { return TypeDescriptor.of(Input.class); }
        @Override public TypeDescriptor<Output> outputType() { return TypeDescriptor.of(Output.class); }
        @Override public Output run(WorkflowContext<Input> context) {
            PromptResult result = context.harness().session("default").prompt(context.input().text());
            return new Output(result.text());
        }
    }
}
```

- [x] **Step 2: Run test and verify it fails**

Run:

```bash
mvn -pl helm-runtime -am test
```

Expected: FAIL because runtime classes do not exist.

- [x] **Step 3: Add fake provider and in-memory store**

Create `FakeProvider.java` and `InMemoryRuntimeStore.java`:

```java
package io.agent.helm.runtime;

import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import io.agent.helm.core.model.ModelRequest;
import io.agent.helm.core.model.ModelStreamEvent;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

public final class FakeProvider implements ModelProvider {
    private final String providerId;
    private final Queue<ModelStreamEvent[]> scripts = new ArrayDeque<>();

    public FakeProvider(String providerId) {
        this.providerId = providerId;
    }

    public void enqueue(ModelStreamEvent... events) {
        scripts.add(Arrays.copyOf(events, events.length));
    }

    @Override public boolean supports(ModelRef model) {
        return providerId.equals(model.providerId());
    }

    @Override public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
        ModelStreamEvent[] events = scripts.remove();
        SubmissionPublisher<ModelStreamEvent> publisher = new SubmissionPublisher<>();
        Thread.startVirtualThread(() -> {
            for (ModelStreamEvent event : events) publisher.submit(event);
            publisher.close();
        });
        return publisher;
    }
}
```

```java
package io.agent.helm.runtime;

import io.agent.helm.core.event.RuntimeEventRecord;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.WorkflowRunRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRuntimeStore implements RuntimeStore {
    private final Map<String, AgentSessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, OperationRecord> operations = new ConcurrentHashMap<>();
    private final Map<String, WorkflowRunRecord> workflowRuns = new ConcurrentHashMap<>();
    private final List<RuntimeEventRecord> events = new ArrayList<>();

    @Override public Optional<AgentSessionState> loadSession(String sessionId) { return Optional.ofNullable(sessions.get(sessionId)); }
    @Override public void saveSession(AgentSessionState session) { sessions.put(session.id(), session); }
    @Override public void saveOperation(OperationRecord operation) { operations.put(operation.id(), operation); }
    @Override public Optional<OperationRecord> loadOperation(String operationId) { return Optional.ofNullable(operations.get(operationId)); }
    @Override public void saveWorkflowRun(WorkflowRunRecord run) { workflowRuns.put(run.id(), run); }
    @Override public Optional<WorkflowRunRecord> loadWorkflowRun(String runId) { return Optional.ofNullable(workflowRuns.get(runId)); }
    @Override public synchronized void appendEvent(RuntimeEventRecord event) { events.add(event); }
    @Override public synchronized List<RuntimeEventRecord> eventsForOperation(String operationId) {
        return events.stream().filter(e -> operationId.equals(e.operationId())).sorted(Comparator.comparingLong(RuntimeEventRecord::sequence)).toList();
    }
    @Override public synchronized List<RuntimeEventRecord> eventsForWorkflowRun(String workflowRunId) {
        return events.stream().filter(e -> workflowRunId.equals(e.workflowRunId())).sorted(Comparator.comparingLong(RuntimeEventRecord::sequence)).toList();
    }
}
```

- [x] **Step 4: Add runtime requests, registries, harness, and agent runtime**

Create `AgentPromptRequest.java`, `ProviderRegistry.java`, `AgentSession.java`, `AgentHarness.java`, and `AgentRuntime.java`:

```java
package io.agent.helm.runtime;

public record AgentPromptRequest(String agentName, String instanceId, String sessionName, String text) {}
```

```java
package io.agent.helm.runtime;

import io.agent.helm.core.error.ProviderNotFoundException;
import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.core.model.ModelRef;
import java.util.List;
import java.util.Map;

final class ProviderRegistry {
    private final List<ModelProvider> providers;
    ProviderRegistry(List<ModelProvider> providers) { this.providers = List.copyOf(providers); }
    ModelProvider resolve(ModelRef model) {
        return providers.stream().filter(provider -> provider.supports(model)).findFirst()
            .orElseThrow(() -> new ProviderNotFoundException("No provider for model", Map.of("model", model.value()), Map.of()));
    }
}
```

```java
package io.agent.helm.runtime;

import io.agent.helm.core.agent.AgentHarnessApi;
import io.agent.helm.core.agent.AgentSessionApi;

final class AgentHarness implements AgentHarnessApi {
    private final AgentRuntime runtime;
    private final String agentName;
    private final String instanceId;

    AgentHarness(AgentRuntime runtime, String agentName, String instanceId) {
        this.runtime = runtime;
        this.agentName = agentName;
        this.instanceId = instanceId;
    }

    @Override public AgentSessionApi session(String sessionName) {
        return new AgentSession(runtime, agentName, instanceId, sessionName);
    }
}
```

```java
package io.agent.helm.runtime;

import io.agent.helm.core.agent.AgentSessionApi;
import io.agent.helm.core.agent.PromptResult;

final class AgentSession implements AgentSessionApi {
    private final AgentRuntime runtime;
    private final String agentName;
    private final String instanceId;
    private final String sessionName;

    AgentSession(AgentRuntime runtime, String agentName, String instanceId, String sessionName) {
        this.runtime = runtime;
        this.agentName = agentName;
        this.instanceId = instanceId;
        this.sessionName = sessionName;
    }

    @Override public PromptResult prompt(String text) {
        return runtime.prompt(new AgentPromptRequest(agentName, instanceId, sessionName, text));
    }
}
```

```java
package io.agent.helm.runtime;

import io.agent.helm.core.agent.AgentConfig;
import io.agent.helm.core.agent.AgentContext;
import io.agent.helm.core.agent.AgentDefinition;
import io.agent.helm.core.agent.PromptResult;
import io.agent.helm.core.error.AgentNotFoundException;
import io.agent.helm.core.error.SessionBusyException;
import io.agent.helm.core.error.ToolExecutionException;
import io.agent.helm.core.message.HelmMessage;
import io.agent.helm.core.store.AgentSessionState;
import io.agent.helm.core.store.OperationRecord;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.tool.Tool;
import io.agent.helm.core.tool.ToolContext;
import io.agent.helm.engine.AgentEngine;
import io.agent.helm.engine.AgentEngineRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentRuntime {
    private final Map<String, AgentDefinition> agents;
    private final ProviderRegistry providers;
    private final RuntimeStore store;
    private final AgentEngine engine = new AgentEngine();
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    private AgentRuntime(Map<String, AgentDefinition> agents, ProviderRegistry providers, RuntimeStore store) {
        this.agents = Map.copyOf(agents);
        this.providers = providers;
        this.store = store;
    }

    public static Builder builder() { return new Builder(); }

    public PromptResult prompt(AgentPromptRequest request) {
        AgentDefinition agent = agents.get(request.agentName());
        if (agent == null) throw new AgentNotFoundException("Agent not found", Map.of("agentName", request.agentName()), Map.of());
        AgentConfig config = agent.configure(new AgentContext(request.agentName(), request.instanceId()));
        String sessionId = request.agentName() + ":" + request.instanceId() + ":" + request.sessionName();
        if (!activeSessions.add(sessionId)) {
            throw new SessionBusyException("Session is busy", Map.of("sessionId", sessionId), Map.of());
        }
        try {
            Instant now = Instant.now();
            AgentSessionState existing = store.loadSession(sessionId).orElse(new AgentSessionState(sessionId, request.agentName(), request.instanceId(), request.sessionName(), 0, List.of(), now, now));
            List<HelmMessage> messages = new ArrayList<>(existing.messages());
            messages.add(HelmMessage.user(request.text()));
            String operationId = "op_" + UUID.randomUUID();
            Map<String, Tool<?, ?>> tools = new HashMap<>();
            config.tools().forEach(tool -> tools.put(tool.name(), tool));
            store.saveOperation(new OperationRecord(operationId, sessionId, "PROMPT", "RUNNING", request.text(), null, Map.of(), now, null));
            var result = engine.run(new AgentEngineRequest(
                config.model(),
                config.instructions(),
                messages,
                providers.resolve(config.model()),
                (ignored, name, input) -> executeTool(operationId, tools, name, input),
                Duration.ofSeconds(30),
                8
            ));
            store.saveSession(new AgentSessionState(sessionId, request.agentName(), request.instanceId(), request.sessionName(), existing.version() + 1, result.messages(), existing.createdAt(), Instant.now()));
            store.saveOperation(new OperationRecord(operationId, sessionId, "PROMPT", "SUCCEEDED", request.text(), result.text(), Map.of(), now, Instant.now()));
            return new PromptResult(operationId, result.text());
        } finally {
            activeSessions.remove(sessionId);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object executeTool(String operationId, Map<String, Tool<?, ?>> tools, String name, Object input) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new ToolExecutionException("Tool not registered", Map.of("tool", name), Map.of());
        }
        try {
            return tool.execute(new ToolContext(operationId), input);
        } catch (Exception e) {
            throw new ToolExecutionException("Tool execution failed", Map.of("tool", name), Map.of("exception", e.getClass().getName()));
        }
    }

    public AgentHarness harness(String agentName, String instanceId) {
        return new AgentHarness(this, agentName, instanceId);
    }

    public static final class Builder {
        private final Map<String, AgentDefinition> agents = new HashMap<>();
        private final List<io.agent.helm.core.model.ModelProvider> providers = new ArrayList<>();
        private RuntimeStore store = new InMemoryRuntimeStore();
        public Builder agent(AgentDefinition agent) { agents.put(agent.name(), agent); return this; }
        public Builder provider(io.agent.helm.core.model.ModelProvider provider) { providers.add(provider); return this; }
        public Builder store(RuntimeStore store) { this.store = store; return this; }
        public AgentRuntime build() { return new AgentRuntime(agents, new ProviderRegistry(providers), store); }
    }
}
```

- [x] **Step 5: Add workflow runtime**

Create `WorkflowInvokeRequest.java`, `WorkflowRunHandle.java`, and `WorkflowRuntime.java`:

```java
package io.agent.helm.runtime;

public record WorkflowInvokeRequest<I>(String workflowName, I input) {}
```

```java
package io.agent.helm.runtime;

public record WorkflowRunHandle<O>(String runId, O result) {}
```

```java
package io.agent.helm.runtime;

import io.agent.helm.core.error.WorkflowNotFoundException;
import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.WorkflowRunRecord;
import io.agent.helm.core.workflow.WorkflowContext;
import io.agent.helm.core.workflow.WorkflowDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkflowRuntime {
    private final Map<String, WorkflowDefinition<?, ?>> workflows;
    private final List<io.agent.helm.core.model.ModelProvider> providers;
    private final RuntimeStore store;

    private WorkflowRuntime(Map<String, WorkflowDefinition<?, ?>> workflows, List<io.agent.helm.core.model.ModelProvider> providers, RuntimeStore store) {
        this.workflows = Map.copyOf(workflows);
        this.providers = List.copyOf(providers);
        this.store = store;
    }

    public static Builder builder() { return new Builder(); }

    @SuppressWarnings("unchecked")
    public <I, O> WorkflowRunHandle<O> invoke(WorkflowInvokeRequest<I> request) {
        WorkflowDefinition<I, O> workflow = (WorkflowDefinition<I, O>) workflows.get(request.workflowName());
        if (workflow == null) throw new WorkflowNotFoundException("Workflow not found", Map.of("workflowName", request.workflowName()), Map.of());
        String runId = "run_" + UUID.randomUUID();
        Instant now = Instant.now();
        store.saveWorkflowRun(new WorkflowRunRecord(runId, request.workflowName(), "RUNNING", request.input(), null, Map.of(), now, null));
        AgentRuntime.Builder agentRuntime = AgentRuntime.builder().agent(workflow.config().agent()).store(store);
        providers.forEach(agentRuntime::provider);
        AgentRuntime runtime = agentRuntime.build();
        O result;
        try {
            result = workflow.run(new WorkflowContext<>() {
                @Override public I input() { return request.input(); }
                @Override public io.agent.helm.core.agent.AgentHarnessApi harness() {
                    return runtime.harness(workflow.config().agent().name(), "workflow-" + runId);
                }
            });
        } catch (Exception e) {
            store.saveWorkflowRun(new WorkflowRunRecord(runId, request.workflowName(), "FAILED", request.input(), null, Map.of("message", e.getMessage()), now, Instant.now()));
            throw new IllegalStateException("Workflow failed", e);
        }
        store.saveWorkflowRun(new WorkflowRunRecord(runId, request.workflowName(), "SUCCEEDED", request.input(), result, Map.of(), now, Instant.now()));
        return new WorkflowRunHandle<>(runId, result);
    }

    public static final class Builder {
        private final Map<String, WorkflowDefinition<?, ?>> workflows = new HashMap<>();
        private final List<io.agent.helm.core.model.ModelProvider> providers = new ArrayList<>();
        private RuntimeStore store = new InMemoryRuntimeStore();
        public Builder workflow(WorkflowDefinition<?, ?> workflow) { workflows.put(workflow.name(), workflow); return this; }
        public Builder provider(io.agent.helm.core.model.ModelProvider provider) { providers.add(provider); return this; }
        public Builder store(RuntimeStore store) { this.store = store; return this; }
        public WorkflowRuntime build() { return new WorkflowRuntime(workflows, providers, store); }
    }
}
```

- [x] **Step 6: Run runtime tests**

Run:

```bash
mvn -pl helm-runtime -am test
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add helm-runtime
git commit -m "feat: add core runtime prompt and workflow execution" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 6: Milestone 1 hardening, docs, and full verification

**Files:**
- Modify: `helm-runtime/src/test/java/io/agent/helm/runtime/AgentRuntimeTest.java`
- Create: `helm-runtime/src/test/java/io/agent/helm/runtime/InMemoryRuntimeStoreTest.java`
- Create: `helm-runtime/src/test/java/io/agent/helm/runtime/EventRedactorTest.java`
- Create: `helm-runtime/src/main/java/io/agent/helm/runtime/EventRedactor.java`
- Create: `docs/examples/milestone-1-agent-workflow.md`

- [x] **Step 1: Add session recovery assertion**

Extend `AgentRuntimeTest` with this test:

```java
@Test
void promptResumesExistingSessionMessages() {
    FakeProvider provider = new FakeProvider("fake");
    provider.enqueue(new ModelStreamEvent.ContentDelta("first"), new ModelStreamEvent.Completed(new TokenUsage(1, 1)));
    provider.enqueue(new ModelStreamEvent.ContentDelta("second"), new ModelStreamEvent.Completed(new TokenUsage(2, 1)));
    InMemoryRuntimeStore store = new InMemoryRuntimeStore();
    AgentRuntime runtime = AgentRuntime.builder()
        .agent(new AssistantAgent())
        .provider(provider)
        .store(store)
        .build();

    runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Hi"));
    runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Again"));

    var session = store.loadSession("assistant:instance-1:default").orElseThrow();
    assertThat(session.version()).isEqualTo(2);
    assertThat(session.messages()).hasSizeGreaterThanOrEqualTo(4);
}
```

- [x] **Step 2: Add event record ordering test**

Create `InMemoryRuntimeStoreTest.java`:

```java
package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.agent.helm.core.event.RuntimeEventRecord;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InMemoryRuntimeStoreTest {
    @Test
    void eventsForOperationAreReturnedBySequence() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        store.appendEvent(new RuntimeEventRecord("evt_2", "op_1", null, 2, "second", Map.of(), Instant.now()));
        store.appendEvent(new RuntimeEventRecord("evt_1", "op_1", null, 1, "first", Map.of(), Instant.now()));

        assertThat(store.eventsForOperation("op_1"))
            .extracting(RuntimeEventRecord::type)
            .containsExactly("first", "second");
    }
}
```

- [x] **Step 3: Add redaction test and implementation**

Create `EventRedactorTest.java`:

```java
package io.agent.helm.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class EventRedactorTest {
    @Test
    void redactsSecretsAndDeveloperDetails() {
        Map<String, Object> redacted = EventRedactor.redact(Map.of(
            "apiKey", "secret",
            "message", "safe",
            "developerDetails", Map.of("path", "/local/path")
        ));

        assertThat(redacted).containsEntry("apiKey", "[REDACTED]");
        assertThat(redacted).containsEntry("message", "safe");
        assertThat(redacted).doesNotContainKey("developerDetails");
    }
}
```

Create `EventRedactor.java`:

```java
package io.agent.helm.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class EventRedactor {
    private static final Set<String> SECRET_KEYS = Set.of("apiKey", "api-key", "authorization", "token", "password", "secret");

    private EventRedactor() {}

    static Map<String, Object> redact(Map<String, Object> payload) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            if ("developerDetails".equals(key)) {
                return;
            }
            if (SECRET_KEYS.contains(key)) {
                redacted.put(key, "[REDACTED]");
            } else {
                redacted.put(key, value);
            }
        });
        return Map.copyOf(redacted);
    }
}
```

- [x] **Step 4: Write minimal usage documentation**

Create `docs/examples/milestone-1-agent-workflow.md`:

````markdown
# Milestone 1 Agent and Workflow Example

This example shows the Milestone 1 API shape using package `io.agent.helm`.

```java
AgentDefinition agent = new AgentDefinition() {
    @Override
    public String name() {
        return "assistant";
    }

    @Override
    public AgentConfig configure(AgentContext context) {
        return AgentConfig.builder()
            .model("fake/test")
            .instructions("You are helpful.")
            .build();
    }
};

FakeProvider provider = new FakeProvider("fake");
provider.enqueue(
    new ModelStreamEvent.ContentDelta("Hello from Helm."),
    new ModelStreamEvent.Completed(new TokenUsage(1, 3))
);

AgentRuntime runtime = AgentRuntime.builder()
    .agent(agent)
    .provider(provider)
    .store(new InMemoryRuntimeStore())
    .build();

PromptResult result = runtime.prompt(new AgentPromptRequest("assistant", "instance-1", "default", "Hi"));
```

The fake provider is deterministic, so Milestone 1 tests can validate runtime behavior without external provider credentials.
````

- [x] **Step 5: Run the full suite**

Run:

```bash
mvn test
```

Expected: PASS for `helm-core`, `helm-agent-engine`, and `helm-runtime`.

- [x] **Step 6: Inspect dependency direction**

Run:

```bash
mvn -q -pl helm-core dependency:tree
mvn -q -pl helm-agent-engine dependency:tree
mvn -q -pl helm-runtime dependency:tree
```

Expected:

1. `helm-core` does not depend on `helm-agent-engine` or `helm-runtime`.
2. `helm-agent-engine` depends on `helm-core`.
3. `helm-runtime` depends on `helm-core` and `helm-agent-engine`.

- [x] **Step 6: Commit**

```bash
git add helm-runtime helm-core docs/examples/milestone-1-agent-workflow.md
git commit -m "test: cover milestone 1 runtime contracts" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Completion status

Completed on 2026-06-28.

Verification performed:

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn clean test` passed from the repository root.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -q test` passed after squashing commits.
- Test reports cover `helm-core`, `helm-agent-engine`, and `helm-runtime` with 34 tests, 0 failures, and 0 errors.
- Production Java package namespace check found no files outside `io.agent.helm`.
- `helm-core` dependency inspection found no dependency on runtime, engine, HTTP, CLI, Spring, Servlet, provider SDKs, JDBC, or logging adapters.

- [x] `mvn test` passes from repository root.
- [x] `AgentRuntime.prompt` returns fake provider terminal text.
- [x] Engine tool-call path is covered by a test.
- [x] `WorkflowRuntime.invoke` persists a workflow run and returns typed output.
- [x] Session recovery increments version and preserves messages.
- [x] `InMemoryRuntimeStore` orders events by sequence.
- [x] Structured errors expose stable code, safe details, and developer details separately.
- [x] Package namespace is `io.agent.helm` in all production Java files.
- [x] `helm-core` has no dependency on runtime, engine, HTTP, CLI, Spring, Servlet, provider SDKs, JDBC, or logging adapters.
