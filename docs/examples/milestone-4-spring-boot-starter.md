# Milestone 4 Spring Boot Starter

Milestone 4 adds `helm-spring-boot-starter` so a Spring Boot application only needs to declare beans
to expose an agent and a workflow. The starter only assembles; no runtime behavior lives in it, and
core Helm modules stay Spring-free.

## Usage

Declare the starter and beans. The auto-configuration discovers `AgentDefinition`,
`WorkflowDefinition<?, ?>`, `ModelProvider`, and `RuntimeStore` beans and builds the runtime. An
`InMemoryRuntimeStore` is provided by default (`@ConditionalOnMissingBean`); supply your own
`RuntimeStore` bean to override. Duplicate agent or workflow names fail fast at startup.

```xml
<dependency>
  <groupId>io.agent</groupId>
  <artifactId>helm-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
@SpringBootApplication
public class Application {
    @Bean AgentDefinition echoAgent() { return new EchoAgent(); }
    @Bean WorkflowDefinition<?, ?> upperWorkflow() { return new UpperWorkflow(); }
    @Bean ModelProvider provider() { return new ConstantFakeProvider("fake"); }
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
```

## Properties

```yaml
helm:
  http:
    enabled: true        # default false; HTTP exposure is opt-in
    route-prefix: ""      # optional path prefix for all Helm routes
```

When `helm.http.enabled=true`, the starter mounts the Milestone 3 `HelmHttpServlet` (built from
`HelmHttpRoutes.router(agentRuntime, workflowRuntime)`) as a `ServletRegistrationBean`. The same
unified error contract applies over HTTP.

## Verification

- `helm-spring-boot-starter` `@SpringBootTest` exercises the routes over the embedded server
  (unknown agent 404, missing field 400, prompt 200, workflow invoke 200, unknown workflow 404).
- `examples/spring-boot-example` is a minimal runnable application with an integration test.
- `mvn verify` is green (141 tests). The Spring Boot BOM is scoped to the starter and example only,
  so core/http/cli modules remain Spring-free (verified via `dependency:tree`).
