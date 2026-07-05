# Helm Error Codes

Stable error code registry for `HelmException`. Codes are stable identifiers exposed to HTTP clients and persisted in
runtime events. New codes are added only by appending to `io.agent.helm.core.error.ErrorCode` and updating this file.

## 注册表

| Code | 异常类 | HTTP status | 含义 |
| --- | --- | --- | --- |
| `AGENT_NOT_FOUND` | `AgentNotFoundException` | 404 | agent 未注册 |
| `WORKFLOW_NOT_FOUND` | `WorkflowNotFoundException` | 404 | workflow 未注册 |
| `PROVIDER_NOT_FOUND` | `ProviderNotFoundException` | 404 | 无 provider 支持 model |
| `OPERATION_NOT_FOUND` | — | 404 | operation id 不存在 |
| `SESSION_NOT_FOUND` | — | 404 | session id 不存在 |
| `PROVIDER_ERROR` | `ProviderException` | 502 | provider 调用失败 |
| `PROVIDER_RATE_LIMITED` | `ProviderException` | 429 | provider 限流 |
| `PROVIDER_TIMEOUT` | `ProviderException` | 504 | provider 超时 |
| `CONTEXT_OVERFLOW` | `ContextOverflowException` | 413 | 上下文超限 |
| `SESSION_BUSY` | `SessionBusyException` | 409 | session 正在执行 |
| `ENGINE_TIMEOUT` | `EngineException` 子类 | 504 | engine turn 超时 |
| `MAX_TURNS_EXCEEDED` | `MaxTurnsExceededException` | 500 | agent loop 超出最大轮数 |
| `MODEL_STREAM_FAILED` | `ModelStreamException` | 502 | 模型流失败 |
| `ENGINE_INTERRUPTED` | `EngineInterruptedException` | 500 | engine 被中断 |
| `TOOL_EXECUTION_FAILED` | `ToolExecutionException` | 500 | tool 执行失败 |
| `TOOL_INPUT_INVALID` | `ToolExecutionException` | 400 | tool 输入校验失败 |
| `TOOL_OUTPUT_INVALID` | `ToolExecutionException` | 500 | tool 输出校验失败 |
| `TOOL_NOT_FOUND` | `ToolExecutionException` | 404 | tool 未注册 |
| `SANDBOX_ERROR` | `SandboxException` | 500 | sandbox 操作失败 |
| `VALIDATION_FAILED` | `ValidationException` | 400 | 请求校验失败 |
| `PERSISTENCE_ERROR` | `PersistenceException` | 500 | 持久化失败 |
| `UNAUTHORIZED` | `AuthorizationException` | 401 | 未认证 |
| `FORBIDDEN` | `AuthorizationException` | 403 | 无权限 |
| `RATE_LIMITED` | `RateLimitExceededException` | 429 | admission 限流 |
| `OPERATION_REJECTED` | — | 429 | operation 被拒绝 |
| `LEASE_LOST` | — | 409 | durable lease 丢失 |
| `RECOVERY_FAILED` | — | 500 | 恢复失败 |

## 字段语义

`HelmException` 三段式：

- `code`：稳定字符串，必为上表注册值，进 HTTP 响应与事件。
- `details`：可安全暴露给 HTTP 调用者与事件的元数据（资源标识符、边界参数、校验字段）。
- `developerDetails`：开发者排错信息，**不进** HTTP 响应 / 事件 payload，默认 redact 后才进日志。

## 暴露规则

| 出口 | code | details | developerDetails |
| --- | --- | --- | --- |
| HTTP 响应 body | 暴露 | 暴露 | 不暴露 |
| `RuntimeEventRecord.payload` | 暴露 | 暴露 | 不暴露 |
| server-side 日志 | 暴露 | 暴露 | 默认 redact |
| 持久化 `OperationRecord.error` | 暴露 | 暴露 | 不暴露 |
