# Changelog

All notable changes to Helm are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/) with the
pre-1.0 convention described in [docs/design/11-api-governance.md](docs/design/11-api-governance.md).

## [Unreleased]

### Added

- 系统设计 M1：`helm-core` 契约层、`helm-agent-engine` 执行引擎、`helm-runtime` 运行时，
  含 `FakeProvider`、`InMemoryRuntimeStore`、基础事件 redaction。
- 系统设计 M2：`helm-provider-openai`、`helm-provider-anthropic` provider 适配器；
  skill loading；`helm-sandbox-local`（InMemory + Local 两种实现）。
- 系统设计 M3：`helm-http-core` framework-neutral HTTP 层、`helm-http-servlet`
  Jakarta Servlet 适配器、`helm-cli`（`helm dev` / `helm run` / `helm inspect`）。
- 系统设计 M4：`helm-spring-boot-starter` 自动配置、bean discovery、conditional HTTP
  route registration、`examples/spring-boot-example`。
- 系统设计 M5：`helm-persistence-jdbc`（`JdbcRuntimeStore` + `JdbcMemoryStore`），
  Flyway schema migrations（`V1__init.sql`、`V2__memory.sql`），event persistence，
  `helm-observability-logging`（`LoggingRuntimeObserver`）。
- 缺口补齐（2026-07-05）：`MemoryStore` SPI 与内置 `save_memory` tool，记忆按 agent
  实例 scope 存储并自动注入 instructions；session 生命周期管理（列出、检查、重置），
  `maxSessionMessages` 历史裁剪。
- 生产化设计文档：streaming API（#1）、engine hardening（#2）、JsonSchema extensions
  （#3）、memory 语义检索（#4）、authorizer security context（#5）、HTTP client SDK
  （#6）、rate limiting（#7）、metrics / OpenTelemetry（#8）、durable scale runtime
  （#9）、release engineering（#10）、API governance（#11）共 11 篇组件设计方案。
- Release engineering 基线：Apache License 2.0、Maven wrapper、GitHub Actions CI、
  `CHANGELOG.md`、`CONTRIBUTING.md`、adapter 实现指南。

### Changed

- 项目 license 决策为 Apache License 2.0（见 `LICENSE`）。

## Notes

- 首个正式发布版本号将为 `0.1.0`。当前所有内容仍在 `[Unreleased]` 段，发布时将其
  改为 `[0.1.0] - <date>` 并新建空 `[Unreleased]` 段。
- 每个 PR 必须在 `[Unreleased]` 下追加对应条目（`Added` / `Changed` / `Fixed` /
  `Security`），条目格式为 `<模块名>: <一句话描述>`。

[Unreleased]: https://github.com/agent-helm/helm/compare/HEAD
