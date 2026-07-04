# Milestone 2 Providers, Skills, and Sandbox

Milestone 2 adds real model provider adapters, classpath skill loading, and local sandbox
implementations on top of the Milestone 1 core runtime. All adapters implement `helm-core` SPI;
no core module gained a new dependency.

## Providers

`helm-provider-openai` and `helm-provider-anthropic` implement `ModelProvider` against the
OpenAI-compatible Chat Completions API and the Anthropic Messages API respectively. Both stream
responses over the JDK `HttpClient` and normalize SSE chunks into `ModelStreamEvent` values.

```java
String apiKey = System.getenv("OPENAI_API_KEY");
if (apiKey == null) {
    throw new IllegalStateException("OPENAI_API_KEY is not set");
}

ModelProvider provider = OpenAiProvider.builder()
    .providerId("openai")
    .baseUrl("https://api.openai.com/v1")   // or a vLLM/Ollama compatible endpoint
    .apiKey(apiKey)
    .build();

AgentRuntime runtime = AgentRuntime.builder()
    .agent(agent)
    .provider(provider)
    .store(new InMemoryRuntimeStore())
    .build();
```

Switching the same agent definition between providers only changes the configured model reference
and the registered provider — no agent code changes. See `ProviderSwitchIntegrationTest`.

Credentials are read from environment variables and never persisted into runtime events or error
details. HTTP 429 maps to `PROVIDER_RATE_LIMITED`, request timeout to `PROVIDER_TIMEOUT`, and other
4xx/5xx responses to `PROVIDER_ERROR`.

## Skills

`ClasspathSkillLoader` discovers skills at `helm/skills/<name>/SKILL.md` (plus sibling resource
files) on the classpath. `SKILL.md` uses YAML-style frontmatter for `name` and `description`; the
body is the skill instructions.

```text
---
name: writer
description: A writing assistant skill
---
You help the user write clearly and concisely.
```

```java
SkillLoader loader = new ClasspathSkillLoader();
List<SkillDefinition> skills = loader.loadAll();
```

Skill names are validated against `[A-Za-z0-9_-]+` so traversal names are rejected before any
classpath lookup. Invalid skills raise `ValidationException`.

## Sandbox

`helm-sandbox-local` provides two `Sandbox` implementations:

- `InMemorySandbox` — a pure in-memory virtual file system with no host access and no shell.
- `LocalSandbox` — a host-rooted file system that rejects absolute paths, traversal, and symlink
  escape. Shell execution is disabled by default; when enabled it runs argv directly with a fixed
  working directory, a hard timeout cap, and a captured-output limit.

```java
Sandbox sandbox = LocalSandbox.builder()
    .root(workDir)
    .shellEnabled(true)
    .shellTimeout(Duration.ofSeconds(5))
    .outputLimitChars(8192)
    .build();
```

## Verification

The provider and sandbox SPIs each have a reusable contract test base (`ModelProviderContractTest`
and `SandboxContractTest`) published from `helm-core`'s test-jar. `FakeProvider`, `InMemorySandbox`,
and `LocalSandbox` pass the contract suites, and both real providers pass `ModelProviderContractTest`
against WireMock. No test depends on real network or credentials.
