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
