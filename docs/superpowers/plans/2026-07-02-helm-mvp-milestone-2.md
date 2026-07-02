# Helm MVP Milestone 2 Design Plan: Provider、Skill、Sandbox

> **Status:** 设计文档，尚未实施。实施前可按需细化为逐任务的可执行计划。

**Goal:** 在 Milestone 1 core runtime 之上交付真实模型 provider（OpenAI 兼容、Anthropic）、classpath skill 加载机制，以及 in-memory 与 local sandbox，使同一个 agent 定义可以在 `FakeProvider` 与真实 provider 之间无代码变更切换。

**Architecture:** 所有交付物都是 `helm-core` SPI 的实现，不修改 core 契约（除非发现 SPI 缺口，需先在 core 补齐并配契约测试）。Provider 模块只依赖 `helm-core` 与 HTTP 客户端（JDK `HttpClient`），不依赖官方 SDK。Sandbox 模块只依赖 `helm-core`。

**Tech Stack:** Java 21、Maven、JUnit 5、AssertJ、WireMock（仅测试范围）、Jackson（provider 协议序列化）。

---

## Scope

- 新模块：`helm-provider-openai`、`helm-provider-anthropic`、`helm-sandbox-local`。
- Skill 加载能力放入 `helm-runtime`（classpath 资源扫描不需要新模块）。
- 不包含：真实网络测试、provider 凭证管理 UI、生产级容器隔离。

## Task 1: Provider SPI 契约测试套件

- 在 `helm-core`（test-jar）或 `helm-runtime` 中提供可复用的 `ModelProviderContractTest` 抽象基类。
- 覆盖：请求构造、流事件顺序（start → delta → tool-call → stop）、错误映射到 `HelmException` 子类、取消行为。
- `FakeProvider` 必须首先通过该契约测试，作为基准实现。

## Task 2: `helm-provider-openai`

- 实现 OpenAI Chat Completions 兼容协议（含兼容端点 base URL 可配置，覆盖 vLLM、Ollama 等兼容服务）。
- SSE 流式响应解析并规范化到 core 的 stream 事件模型。
- Tool call 增量拼装（分片的 function call arguments 聚合）。
- 结构化错误映射：HTTP 4xx/5xx、限流、超时分别映射到稳定错误码。
- 测试：WireMock 模拟协议（正常流、tool call 流、错误、截断流），通过 Provider 契约测试。

## Task 3: `helm-provider-anthropic`

- 实现 Anthropic Messages API 协议与其 SSE 事件模型（`message_start`、`content_block_delta` 等）到 core stream 事件的规范化。
- Tool use 块与 tool result 的双向映射。
- 与 Task 2 相同的错误映射与 WireMock 测试策略，通过 Provider 契约测试。

## Task 4: Skill classpath 加载

- 在 `helm-runtime` 增加 `ClasspathSkillLoader`：按约定目录（如 `helm/skills/<name>/SKILL.md` + 附属文件）从 classpath 发现并加载 `SkillDefinition`。
- Skill 元数据校验（名称、描述必填），非法 skill 报结构化错误。
- 测试：test resources 中放置示例 skill，验证发现、加载、附属文件读取与错误路径。

## Task 5: `helm-sandbox-local`

- `InMemorySandbox`：纯内存文件系统，读写、列目录、路径规范化与越界防护。
- `LocalSandbox`：绑定到宿主机指定根目录的受控文件系统；所有路径解析后必须落在根目录内（防路径穿越）。
- Shell 能力：默认禁用；显式开启后限制工作目录、超时与输出大小。
- Sandbox SPI 契约测试套件，两个实现都要通过。

## Task 6: 切换验证与文档

- 集成测试：同一个 `AgentDefinition` 分别配置 `FakeProvider` 与 WireMock 后端的 OpenAI provider，行为一致（除模型输出内容）。
- 更新 `docs/examples/`：真实 provider 配置示例（凭证从环境变量读取，不落仓库）。

## 验收标准

1. `mvn verify` 全绿（编译 + 单测 + Spotless）。
2. Provider SPI 与 Sandbox SPI 均有契约测试，且所有实现通过。
3. 测试不依赖真实网络与真实凭证。
4. core 三模块不新增任何依赖。
