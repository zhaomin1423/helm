# Persistence Adapter 实现指南

本指南说明如何为 Helm 实现持久化 adapter，包括 `RuntimeStore`（含子接口）与 `MemoryStore`。

## 1. SPI 概览

### 1.1 RuntimeStore 聚合 facade

`RuntimeStore` 是四个子接口的聚合 facade，位于 `io.agent.helm.core.store`：

```java
public interface RuntimeStore extends SessionStore, OperationStore, WorkflowRunStore, EventStore {}
```

| 子接口 | 职责 |
| --- | --- |
| `SessionStore` | agent session 状态的加载、保存、列出、删除 |
| `OperationStore` | operation 记录的加载、保存、列出 |
| `WorkflowRunStore` | workflow run 记录的加载、保存、列出 |
| `EventStore` | 运行时事件的追加与按 operation / workflow run 查询 |

现有实现：`InMemoryRuntimeStore`（`helm-runtime`）、`JdbcRuntimeStore`（`helm-persistence-jdbc`）。

### 1.2 MemoryStore

`MemoryStore` 是跨 session 长期记忆的 SPI，位于 `io.agent.helm.core.memory`：

```java
public interface MemoryStore {
    void save(MemoryRecord memory);
    Optional<MemoryRecord> load(String memoryId);
    List<MemoryRecord> list(String scopeId);
    List<MemoryRecord> search(String scopeId, String query);
    void delete(String memoryId);
}
```

现有实现：`InMemoryMemoryStore`（`helm-runtime`）、`JdbcMemoryStore`（`helm-persistence-jdbc`）。

## 2. 实现选择

### 2.1 实现完整 RuntimeStore

适合通用持久化后端（JDBC、MongoDB、Redis）。extend `RuntimeStoreContractTest` 验证全部四个子接口合约。

### 2.2 仅实现单个子接口

适合专用存储（如只做 event sink 的 `EventStore`，或只存 session 的 `SessionStore`）。adapter 可 `implements EventStore` 单独发布。对应子接口的 ContractTest 基类未来在 `helm-runtime-testkit` 提供。

### 2.3 实现 MemoryStore

独立于 RuntimeStore，extend `MemoryStoreContractTest` 验证合约。

## 3. 合约测试

必须通过对应合约测试基类（当前位于 `helm-core` test-jar，未来迁移至 `helm-runtime-testkit`）：

| 实现目标 | ContractTest 基类 |
| --- | --- |
| `RuntimeStore` 全接口 | `RuntimeStoreContractTest` |
| `MemoryStore` | `MemoryStoreContractTest` |

示例：

```java
package io.agent.helm.persistence.example;

import io.agent.helm.core.store.RuntimeStore;
import io.agent.helm.core.store.RuntimeStoreContractTest;

class ExampleRuntimeStoreContractTest extends RuntimeStoreContractTest {
    @Override
    protected RuntimeStore createStore() {
        return new ExampleRuntimeStore(/* connection config */);
    }
}
```

合约覆盖（见 [docs/contracts/runtime-store.md](../contracts/runtime-store.md)）：

- `saveSession` / `saveOperation` / `saveWorkflowRun` 替换同 id 的当前记录。
- `load*` 对未知 id 返回 `Optional.empty()`。
- `listOperations` / `listWorkflowRuns` 按 `createdAt` 升序。
- `appendEvent` 追加事件；`eventsForOperation` / `eventsForWorkflowRun` 按 `sequence` 升序。
- 返回列表是快照，调用者修改不影响 store 内部。

## 4. Session version / 乐观锁

`AgentSessionState` 含 `version` 字段。当前合约不强制乐观锁（InMemory 实现是 best-effort thread-safe），但 JDBC 实现应在 `saveSession` 时校验 version 递增，避免并发覆盖。durable scale（组件 #9）落地后会强制 lease 与 version 校验。

## 5. Event ordering / append-only

`EventStore` 是 append-only 日志：

- `appendEvent` 只追加，不更新已有事件。
- 事件 `sequence` 在同一 `operationId` 或 `workflowRunId` 范围内单调递增。
- 查询按 `sequence` 升序返回。

## 6. Schema migration（Flyway）

JDBC adapter 用 Flyway 管理 schema。参考 `helm-persistence-jdbc` 的迁移文件：

```text
src/main/resources/db/migration/
  V1__init.sql          # helm_session / helm_operation / helm_workflow_run / helm_event
  V2__memory.sql        # helm_memory
```

新 adapter 应遵循版本递增约定（`V<n>__<description>.sql`），每个迁移文件幂等且向前兼容。迁移文件命名 `V<序号>__<描述>.sql`，序号不可复用。

`V1__init.sql` 关键表结构参考：

```sql
CREATE TABLE helm_session (
    id           VARCHAR(255) NOT NULL PRIMARY KEY,
    agent_name   VARCHAR(255) NOT NULL,
    instance_id  VARCHAR(255) NOT NULL,
    session_name VARCHAR(255) NOT NULL,
    version      BIGINT NOT NULL,
    messages     CLOB NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL
);

CREATE TABLE helm_event (
    id              VARCHAR(255) NOT NULL PRIMARY KEY,
    operation_id    VARCHAR(255),
    workflow_run_id VARCHAR(255),
    sequence        BIGINT NOT NULL,
    type            VARCHAR(128) NOT NULL,
    payload         CLOB,
    created_at      TIMESTAMP NOT NULL
);
```

## 7. SQL 异常映射

数据库异常必须映射为 `PersistenceException`（code = `PERSISTENCE_ERROR`）：

```java
try {
    jdbcTemplate.update(sql, params);
} catch (DataAccessException e) {
    throw new PersistenceException("PERSISTENCE_ERROR", "failed to save session: " + sessionId,
            /*details*/ Map.of("sessionId", sessionId), /*developerDetails*/ e.getMessage());
}
```

`developerDetails` 可含 SQL state 等 debug 信息，不进 HTTP 响应与事件 payload。

## 8. 安全约束

- store 实现不应向 operation error、workflow error 或 event payload 添加 developer-only 详情。
- store 应原样持久化 runtime 已 redact 的 payload。
- provider credential 与应用 secret 不得由持久化 adapter 引入。

## 9. 依赖约束

持久化 adapter 只依赖 `helm-core`（SPI 类型）+ 持久化驱动（JDBC driver / MongoDB driver 等）。不依赖 `helm-runtime` 或 `helm-agent-engine`。

## 10. 发布与 BOM 注册

模块完成后在 `helm-bom` 注册坐标。详见 [docs/design/10-release-engineering.md](../design/10-release-engineering.md) §3.2.3。
