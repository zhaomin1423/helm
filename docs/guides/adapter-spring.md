# Spring Boot Starter 集成指南

本指南说明如何使用 `helm-spring-boot-starter` 将 Helm 集成到 Spring Boot 应用。

## 1. 定位

`helm-spring-boot-starter` 是集成层，**不属于 core**。它负责：

- 自动配置 `AgentRuntime` / `WorkflowRuntime`。
- 通过 bean discovery 收集应用中的 `AgentDefinition` / `WorkflowDefinition` / `ModelProvider` / `RuntimeStore`。
- 按条件挂载 HTTP servlet（默认关闭）。

core / engine / runtime 模块保持 Spring-free，starter 仅做装配，不承载 runtime 行为。

## 2. 引入依赖

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.agent.helm</groupId>
      <artifactId>helm-bom</artifactId>
      <version>${helm.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.agent.helm</groupId>
    <artifactId>helm-spring-boot-starter</artifactId>
  </dependency>
</dependencies>
```

## 3. 自动配置

`HelmAutoConfiguration`（`@AutoConfiguration`）自动装配以下 bean：

| Bean | 来源 | 默认行为 |
| --- | --- | --- |
| `RuntimeStore` | 容器中无自定义 bean 时 | `InMemoryRuntimeStore` |
| `AgentRuntime` | 收集所有 `AgentDefinition` + `ModelProvider` + `RuntimeStore` | 按 builder 组装 |
| `WorkflowRuntime` | 收集所有 `WorkflowDefinition` + `ModelProvider` + `RuntimeStore` | 按 builder 组装 |
| `HelmHttpServlet` | `helm.http.enabled=true` 时 | 注册为 Servlet |

## 4. Bean discovery

应用只需声明 Spring bean，starter 自动收集：

```java
@Configuration
class HelmConfig {

    @Bean
    public ModelProvider openaiProvider() {
        return new OpenAiModelProvider(System.getenv("OPENAI_API_KEY"));
    }

    @Bean
    public AgentDefinition assistant() {
        return new AssistantAgent();
    }

    @Bean
    public WorkflowDefinition<ReviewInput, ReviewOutput> reviewWorkflow() {
        return new ReviewWorkflow();
    }
}
```

starter 会校验 agent / workflow 名称唯一，重名时 fail-fast：

```text
IllegalStateException: duplicate agent name: assistant
```

## 5. 配置属性

所有属性在 `helm.*` 前缀下（`HelmProperties`）：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `helm.http.enabled` | `false` | 是否挂载 HTTP servlet（安全默认关闭） |
| `helm.http.routePrefix` | `""` | 路由前缀，如 `/api` |

`application.yml` 示例：

```yaml
helm:
  http:
    enabled: true
    route-prefix: /api
```

## 6. HTTP route 注册

HTTP servlet 默认**不挂载**（安全默认）。启用方式：

```yaml
helm:
  http:
    enabled: true
```

启用后，`HelmHttpServlet` 注册到 `${helm.http.routePrefix}/*`，提供 prompt / dispatch / inspect 等 HTTP endpoint。

未启用 HTTP 时，应用仍可通过注入 `AgentRuntime` / `WorkflowRuntime` 以编程方式调用。

## 7. 自定义 RuntimeStore

默认使用 `InMemoryRuntimeStore`（非持久）。生产环境应替换为 JDBC 等持久实现：

```java
@Bean
public RuntimeStore runtimeStore(DataSource dataSource) {
    return new JdbcRuntimeStore(dataSource);
}
```

starter 通过 `@ConditionalOnMissingBean(RuntimeStore.class)` 检测：容器中已有自定义 `RuntimeStore` bean 时不再创建默认 InMemory 实现。

## 8. Spring-free 校验

core / engine / runtime 模块不依赖 Spring。校验方法：

```bash
./mvnw -pl helm-core,helm-agent-engine,helm-runtime dependency:tree | grep -i spring
```

应无输出。Spring 仅在 `helm-spring-boot-starter` 与 `helm-http-servlet`（Servlet API）层引入。

## 9. 现有示例

参考 `examples/spring-boot-example`：完整 Spring Boot 应用，含 agent 定义、tool、HTTP 启用。

```bash
./mvnw -pl examples/spring-boot-example -am spring-boot:run
```

## 10. 发布与 BOM 注册

starter 已在 `helm-bom` 注册。详见 [docs/design/10-release-engineering.md](../design/10-release-engineering.md) §3.2.3。
