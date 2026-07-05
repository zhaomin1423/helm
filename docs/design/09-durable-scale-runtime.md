# 09 · Durable Scale Runtime（M11 Post-GA）

> 状态：前瞻设计 · 来源：[`docs/roadmap.md`](../roadmap.md) M11 全部 + §3.1「并发/队列调度（M11）」「remote sandbox（M11）」
> 关联：[#1 streaming](01-streaming-api.md) · [#5 authorizer](05-authorizer-security-context.md) · [#7 rate limiting](07-rate-limiting.md) · [#8 metrics](08-metrics-opentelemetry.md) · sandbox ✓ · persistence-jdbc ✓
>
> **本设计为 Post-GA 前瞻**，不急于实现。M11 的复杂度在于 lease/recovery 与 idempotency，必须等同步 runtime（M0–M10）稳定后再启动（见 roadmap §6 偏差记录「将高级 durable execution 作为 Post-GA 方向」）。

## 1. 背景与目标

### 1.1 为什么需要

当前 `AgentRuntime.executePrompt`（`AgentRuntime.java:127-240`）是**同步**执行：

- 调用方阻塞在 `engine.run(...)` 直到所有 turn 完成。
- `activeSessions` 锁（`AgentRuntime.java:51,129,238`）只防同 session 并发，不防进程崩溃——进程崩溃后锁丢失，operation 卡在 `RUNNING` 永远不会完成。
- 无队列：高并发下无背压，provider 限流直接抛错。
- 无 cancellation：`prompt` 一旦开始无法中断。
- 无 provider fallback：主 provider 5xx 直接失败。
- sandbox 只能在本进程（`InMemorySandbox` / `LocalSandbox`），无法隔离不受信代码。

生产场景需要：提交后异步执行、崩溃可恢复、可取消、provider 可降级、sandbox 可远程隔离。

### 1.2 目标

| 目标 | 说明 |
| --- | --- |
| async admission | `dispatch`/`invoke` 提交后立即返回 handle，operation 异步执行 |
| per-session FIFO | 同 session 的 operation 串行保序；跨 session 并行 |
| lease / recovery | worker 崩溃后 lease 过期，operation 被重新入队，不卡死 |
| turn journal | 每 turn 持久化，mid-turn 崩溃可从最近完成 turn 恢复 |
| stream recovery | streaming 中断后可续传 |
| cancellation | `OperationHandle.cancel()` cooperative 取消 |
| provider routing/fallback | 多 provider 按优先级 + 健康度路由，失败降级 |
| remote sandbox | sandbox 可远程执行（container/远程服务） |

### 1.3 不解决什么

- 不做跨进程分布式调度（单进程内的 durable 模式先行）。
- 不盲目重放不确定副作用（side-effecting tool retry 需 idempotency policy，见 §3.9）。
- 不替代 #7 rate limiting——rate limit 在 admission 前，durable queue 在 admission 后。

## 2. 现状与缺口

### 2.1 同步执行路径

`AgentRuntime.executePrompt`（`AgentRuntime.java:127-240`）：

```text
prompt(request)
 → sessionId, activeSessions.putIfAbsent（session 锁）
 → saveOperation(RUNNING) + appendEvent(OPERATION_STARTED)
 → loadSession → append user msg → trimHistory → saveSession
 → engine.run(...)                          ← 阻塞，turn 循环
 → saveSession(updated) + saveOperation(SUCCEEDED) + appendEvent(OPERATION_SUCCEEDED)
 → finally activeSessions.remove
```

崩溃点：engine.run 中途进程死 → operation 永远 `RUNNING`，session 锁状态不一致（InMemoryStore 丢失；JdbcStore 残留 `RUNNING` 记录）。

### 2.2 缺口清单

| 能力 | 现状 | M11 目标 |
| --- | --- | --- |
| async execution | 同步阻塞 | 异步 + handle |
| 持久队列 | 无 | DB-backed WorkQueue |
| per-session FIFO | activeSessions 仅防并发 | 分片 FIFO |
| lease | 无 | claim + TTL + renew + recovery |
| turn journal | saveSession 是 turn 粒度但无显式 journal | 显式 TurnJournal |
| stream recovery | 无（#1 stream 中断即丢） | chunk journal + offset |
| cancellation | 无 | cooperative cancel |
| provider routing | ProviderRegistry.resolve 单一 | ProviderRouter + fallback chain |
| remote sandbox | InMemory/Local | RemoteSandbox（container） |
| OperationStatus | RUNNING/SUCCEEDED/FAILED | + QUEUED/CANCELLING/CANCELLED/RECOVERING |

### 2.3 roadmap 出处

- `docs/roadmap.md` M11 交付项全部 8 条。
- `docs/roadmap.md` §3.1「并发/队列调度（M11）」「remote sandbox（M11）」。
- `docs/roadmap.md` §6 偏差记录「将高级 durable execution 作为 Post-GA 方向」。
- M11 验收：accepted work 可恢复且语义有文档、不盲目重放不确定副作用、side-effecting tool retry 需 idempotency policy。

## 3. 设计方案

### 3.1 整体架构

```text
dispatch/prompt ──► AdmissionGateway
                      │  [#5 authorizer] → [#7 rate limiter]
                      ▼
                   WorkQueue（DB-backed）
                      │  append OperationRecord(QUEUED)
                      ▼
                   WorkerPool
                      │  claim(operationId, workerId, leaseTtl)
                      │  renew(leaseId, ttl) 周期续约
                      ▼
                   AgentEngine（#2 hardening）
                      │  每 turn 写 TurnJournal
                      ▼
                   complete → saveOperation(SUCCEEDED) + release(lease)
                      │
              crash? ──┘
                      ▼
                   LeaseManager 超时 → RecoveryService 重新入队
                      │  从 TurnJournal 恢复最近完成 turn
                      ▼
                   重新 claim 执行
```

组件清单：

| 组件 | 职责 | 模块归属 |
| --- | --- | --- |
| `AdmissionGateway` | authorizer + rate limiter + enqueue | helm-runtime |
| `WorkQueue` | 持久化排队、claim/release/requeue | helm-runtime（接口）+ persistence-jdbc（实现） |
| `WorkerPool` | 拉取队列、执行 engine | helm-runtime |
| `LeaseManager` | lease TTL、续约、过期检测 | helm-runtime |
| `TurnJournal` | turn 粒度进度持久化 | helm-runtime（接口）+ persistence-jdbc（实现） |
| `RecoveryService` | 检测过期 lease、重新入队 | helm-runtime |
| `ProviderRouter` | 多 provider 路由 + fallback | helm-runtime |
| `RemoteSandbox` | 远程 sandbox SPI 实现 | helm-sandbox-remote（新） |

### 3.2 模块归属

durable 能力并入 `helm-runtime`（可选模式），不新增 `helm-runtime-durable`：

```java
AgentRuntime.builder()
    .durable(true)                          // 启用 async + queue + lease
    .workQueue(workQueue)                    // 注入持久队列实现
    .workerPool(new WorkerPool(4))           // worker 数
    .leaseTtl(Duration.ofSeconds(60))
    .providerRouter(ProviderRouter.fallback(...))
    .build();
```

`durable(false)`（默认）保持现有同步行为，向后兼容。`WorkQueue`/`TurnJournal` 是 SPI（放 `helm-core` `core.store` 子包），内存实现用于测试，JDBC 实现用于生产。

### 3.3 WorkQueue SPI

```java
public interface WorkQueue {
    /** 入队，返回 enqueue 序号（持久化顺序）。 */
    long enqueue(OperationRecord operation);

    /** 按 sessionId 分片，claim 最早未处理项。 */
    Optional<QueueItem> claim(String workerId, Duration leaseTtl);

    /** 续约。返回 false 表示 lease 已被回收。 */
    boolean renew(String leaseId, Duration ttl);

    /** 完成并释放。 */
    void complete(String leaseId, OperationStatus terminalStatus);

    /** 重新入队（lease 过期或 worker 主动 requeue）。 */
    void requeue(String leaseId);

    /** 列出过期未续约的 lease，供 RecoveryService 处理。 */
    List<QueueItem> expiredLeases(Instant now);
}
```

**per-session FIFO 分片**：`claim(workerId, ttl)` 在 SQL 里按 `sessionId` 分片，保证同 session 内 FIFO（`ORDER BY enqueueSeq ASC`），跨 session 并行。给出 JDBC SQL 展望：

```sql
-- claim（PostgreSQL，FOR UPDATE SKIP LOCKED）
UPDATE helm_work_queue
SET worker_id = ?, lease_id = ?, lease_expires_at = NOW() + ?
WHERE id = (
  SELECT id FROM helm_work_queue
  WHERE status = 'QUEUED'
  ORDER BY session_id, enqueue_seq
  FOR UPDATE SKIP LOCKED
  LIMIT 1
)
RETURNING *;
```

### 3.4 LeaseManager

lease 状态机：

```text
claim ─► LEASED ──renew──► LEASED（续约）
              │
              │ TTL 过期
              ▼
           EXPIRED ──► RecoveryService.requeue ──► QUEUED
              │
              │ complete
              ▼
           RELEASED（终态）
```

`LeaseManager` 后台线程周期扫描 `expiredLeases(now)`，对每个过期 lease 调 `requeue`。续约由 worker 在 engine 每 turn 边界调用 `renew`（#2 engine hardening 的 turn 事件可触发）。

### 3.5 TurnJournal

```java
public interface TurnJournal {
    /** 记录一个 turn 的完成状态（不含 message 全量，只存指针/摘要）。 */
    void append(TurnEntry entry);

    /** 读取某 operation 最近 N 个完成 turn，用于恢复。 */
    List<TurnEntry> tail(String operationId, int lastN);

    /** 截断到指定 turn（恢复时丢弃半成品 turn）。 */
    void truncate(String operationId, long upToTurnIndex);
}

record TurnEntry(String operationId, long turnIndex, TurnStatus status,
                 TokenUsage usage, Instant completedAt) {}
```

恢复语义：worker 崩溃后，`RecoveryService` 重新 claim，调用 `tail(operationId, 1)` 取最近完成 turn，从下一 turn 继续执行，丢弃崩溃 turn 的部分 tool 副作用（除非 tool 声明 idempotent，见 §3.9）。

### 3.6 stream chunk recovery

streaming #1 的 `promptStream` 在 durable 模式下：

- 每个 `ContentDelta` 写 chunk journal（`StreamChunkJournal`）。
- 客户端断开重连时，`GET /operations/{id}/stream?offset=N` 从 offset 续传。
- chunk journal 有 TTL（如 5 分钟），过期清理。

```java
public interface StreamChunkJournal {
    void append(String operationId, long offset, PromptStreamEvent event);
    List<PromptStreamEvent> readFrom(String operationId, long offset);
    void delete(String operationId);
}
```

### 3.7 cancellation

`OperationStatus` 扩展：

```java
public enum OperationStatus {
    QUEUED, RUNNING, CANCELLING, CANCELLED, RECOVERING, SUCCEEDED, FAILED
}
```

转换：

```text
QUEUED ─► RUNNING ─► SUCCEEDED | FAILED
  │         │
  │         │ cancel()
  │         ▼
  │      CANCELLING ─► CANCELLED
  │         │
  │         │ lease 过期
  │         ▼
  │      RECOVERING ─► RUNNING
  ▼
CANCELLED（从队列移除）
```

`OperationHandle.cancel()`（#1 已有 handle 形态）设置 `CANCELLING`，worker 在 **turn 边界** + **tool 执行前** 检查 cancellation flag，cooperative 退出。已开始的 tool 调用不强制中断（避免副作用不一致）。

### 3.8 ProviderRouter

```java
public interface ProviderRouter {
    /** 按优先级 + 健康度选 provider。 */
    ModelProvider select(ModelRef model);

    /** 记录 provider 调用结果，更新健康度。 */
    void record(ModelProvider provider, ProviderOutcome outcome);
}
```

默认实现 `FallbackProviderRouter`：按注册顺序 + 连续失败计数降级。`record` 在 #2 的 MODEL_ENDED/FAILED 事件触发。失败到阈值后短期熔断（不再 select）。

与现有 `ProviderRegistry.resolve`（`helm-runtime/.../ProviderRegistry.java`）关系：`ProviderRouter` 包装 registry，`select` 委托 registry 但加 fallback 逻辑。

### 3.9 Idempotency Policy

side-effecting tool retry 不能盲目重放。扩展 `Tool` SPI：

```java
public interface Tool<I, O> {
    // 现有方法...
    /** 默认 NOT_IDEMPOTENT；idempotent tool 可在崩溃后安全重放。 */
    default Idempotency idempotency() { return Idempotency.NOT_IDEMPOTENT; }
}

public enum Idempotency {
    NOT_IDEMPOTENT,   // 崩溃后不重放该 turn，跳过（RECOVERING 模式从下一 turn）
    IDEMPOTENT,       // 可安全重放（如只读 tool、基于 idempotency key 的写入）
}
```

恢复策略：
- `IDEMPOTENT` tool：重放该 turn。
- `NOT_IDEMPOTENT` tool：不重放，把 operation 标 `FAILED` 并附 `recovery=ABORTED_NON_IDEMPOTENT_TOOL`，由应用决定人工处理。

### 3.10 RemoteSandbox

`Sandbox` 是 core SPI（`helm-core/.../sandbox/Sandbox.java`），已有 `InMemorySandbox`/`LocalSandbox`。新增 `RemoteSandbox`：

```java
public interface Sandbox {
    // 现有方法...
}

// helm-sandbox-remote 模块
public final class RemoteSandbox implements Sandbox {
    private final SandboxClient client;  // gRPC 或 HTTP

    public RemoteSandbox(URI endpoint, SandboxCredentials creds) { ... }

    public SandboxFileSystem fileSystem() { return new RemoteFileSystem(client); }
    public SandboxShell shell() { return new RemoteShell(client); }
}
```

`helm-sandbox-remote` 依赖 `helm-core`（Sandbox SPI）+ transport（gRPC 或 HTTP，见 U-2）。container sandbox（Docker exec）作为 `RemoteSandbox` 的本地变体，可在同模块或独立模块。

## 4. 数据流与时序

### 4.1 正常 async 路径

```text
caller.dispatch(req)
 → AdmissionGateway: authorizer(#5) → rate limiter(#7)
 → WorkQueue.enqueue(op, QUEUED) → saveOperation(QUEUED) + OPERATION_QUEUED event
 → return OperationHandle(opId, QUEUED)

WorkerPool (async)
 → WorkQueue.claim(workerId, ttl) → op
 → renew lease; saveOperation(RUNNING) + OPERATION_STARTED
 → for each turn:
     → check cancellation; if CANCELLING → saveOperation(CANCELLED) + complete
     → engine.runTurn → TurnJournal.append(turnEntry)
     → renew lease
 → saveSession + saveOperation(SUCCEEDED) + OPERATION_SUCCEEDED
 → WorkQueue.complete(leaseId, SUCCEEDED)
```

### 4.2 崩溃恢复时序

```text
T0: worker claim op, lease TTL=60s
T1: worker 执行 turn 2，崩溃
T2: lease 未续约
T3(=T0+60s): LeaseManager 扫描 expiredLeases → 发现 op
  → requeue(leaseId) → op 回 QUEUED
  → saveOperation(RECOVERING) + OPERATION_RECOVERING event
T4: 另一 worker claim op
  → TurnJournal.tail(opId, 1) → 最近完成 turn = 1
  → truncate(opId, 1)（丢弃 turn 2 半成品）
  → 从 turn 2 重新执行（IDEMPOTENT tool 重放，非幂等 tool 见 §3.9）
```

### 4.3 cancellation 时序

```text
caller.cancel(opId)
 → saveOperation(CANCELLING) + OPERATION_CANCELLING event
worker at turn boundary:
 → 检测 CANCELLING → saveOperation(CANCELLED) + OPERATION_CANCELLED
 → WorkQueue.complete(leaseId, CANCELLED)
 → 不再执行剩余 turn
```

### 4.4 provider fallback 时序

```text
engine.turn → ProviderRouter.select(model) → providerA
  → providerA.stream → onError(5xx)
  → ProviderRouter.record(providerA, FAILED) → 熔断 providerA
  → ProviderRouter.select(model) → providerB（fallback）
  → providerB.stream → 成功
```

## 5. 安全与边界

- **不盲目重放**：§3.9 idempotency policy 强制非幂等 tool 崩溃后不重放，避免重复发邮件/扣款。
- **lease TTL**：必须远大于单 turn 最大时长（`DEFAULT_MAX_TURNS` × timeout），否则正常执行被误判过期。建议 TTL = `maxTurns * timeout * 2`。
- **credential 不进 journal**：TurnJournal 只存 turnIndex/status/usage，不存 message 内容（message 已在 session state）；StreamChunkJournal 存 ContentDelta 但有 TTL + 不含 credential（依赖 EventRedactor）。
- **sandbox 隔离**：RemoteSandbox 是真正的进程/容器隔离，弥补 LocalSandbox「非生产隔离」的局限（roadmap §2 原则 9）。
- **authorizer 仍生效**：async 模式下 authorizer 在 admission 阶段执行（§4.1），与同步模式一致；recovery 重新执行时不再 authorizer（已授权），但记录 RECOVERING event。
- **依赖守则**：`WorkQueue`/`TurnJournal`/`StreamChunkJournal` 是 core SPI；JDBC 实现在 `helm-persistence-jdbc`；内存实现在 `helm-runtime`（测试用）；`RemoteSandbox` 在 `helm-sandbox-remote`。

## 6. 测试策略

### 6.1 `WorkQueueContractTest`

放 testkit（#10），in-memory 与 JDBC 共用：

- FIFO 顺序（同 session 保序）。
- claim 并发安全（两 worker 不会 claim 同一 item）。
- lease 过期 → `expiredLeases` 返回。
- requeue 后可被重新 claim。
- complete 后不再被 claim。

### 6.2 `LeaseRecoveryContractTest`

- 模拟 worker crash（不调 renew）→ lease 过期 → RecoveryService 重新入队。
- 模拟 worker 慢（renew 成功）→ lease 不过期。
- 续约失败（lease 已被回收）→ worker 应停止处理。

### 6.3 `TurnJournalContractTest`

- append + tail 顺序。
- truncate 丢弃半成品。
- 恢复后从正确 turn 继续。

### 6.4 cancellation 测试

- `cancel` 在 QUEUED 状态 → 直接 CANCELLED，不入 engine。
- `cancel` 在 RUNNING → worker 在 turn 边界检测 → CANCELLED。
- `cancel` 在 tool 执行中 → 等当前 tool 完成再取消。

### 6.5 idempotency 测试

- IDEMPOTENT tool 崩溃后重放 → 副作用执行 1 次（模拟 tool 用 idempotency key 去重）。
- NOT_IDEMPOTENT tool 崩溃后 → operation FAILED + recovery=ABORTED_NON_IDEMPOTENT_TOOL。

### 6.6 ProviderRouter 测试

- 主 provider 5xx → fallback provider。
- 熔断阈值后不再 select 主 provider。
- 恢复后重新 select。

### 6.7 RemoteSandbox 契约测试

复用 `SandboxContractTest`（`helm-core` test-jar 已有），用 FakeRemoteSandboxServer 模拟远程。

## 7. 验收标准

对齐 roadmap M11 验收：

- [ ] accepted work 可恢复且语义有文档（§4.2 + §3.5 + §3.9 文档化恢复语义）。
- [ ] 不盲目重放不确定副作用（§3.9 idempotency policy 强制）。
- [ ] side-effecting tool retry 需 idempotency policy（`Tool.idempotency()` 默认 NOT_IDEMPOTENT）。
- [ ] `WorkQueueContractTest` in-memory + JDBC 双通过。
- [ ] crash recovery 端到端测试通过（kill worker → 另一 worker 接管 → operation SUCCEEDED）。
- [ ] cancellation：QUEUED/RUNNING 两态都可取消。
- [ ] provider fallback：主 provider 失败 → fallback 成功。
- [ ] `OperationStatus` 状态机转换有测试覆盖。
- [ ] `durable(false)` 模式与现有同步行为完全一致（回归测试）。
- [ ] RemoteSandbox 通过 `SandboxContractTest`。

## 8. 风险与未决项

### 8.1 风险

| 风险 | 缓解 |
| --- | --- |
| lease TTL 设短导致正常执行被误回收 | TTL = `maxTurns * timeout * 2`；worker 每 turn renew |
| 非幂等 tool 崩溃后 operation FAILED，业务需人工处理 | §3.9 显式 policy + RECOVERING event 暴露；文档明示 |
| WorkQueue DB 成为瓶颈 | 分片（per-session）+ SKIP LOCKED；后续可换外部队列（Kafka/Redis Streams），SPI 已抽象 |
| stream chunk journal 内存膨胀 | TTL + 后台清理；chunk 不含 message 全量 |
| RemoteSandbox 网络故障 | transport 超时 + 重试；sandbox 调用属外部调用，失败映射 SandboxException |
| 双重执行（lease 未过期但 worker 实际还活着，另一 worker 接管） | renew 频率 > TTL/3；complete 前检查 lease 仍属自己（optimistic lock on leaseId） |

### 8.2 未决项

- U-1：WorkQueue 是否支持外部队列后端（Kafka/Redis Streams），还是仅 DB-backed。SPI 已抽象，实现可选。
- U-2：RemoteSandbox transport 用 gRPC 还是 HTTP（gRPC 流式更适合 streaming，但引入 grpc-java 依赖）。
- U-3：跨进程调度（多实例 WorkerPool 共享 DB 队列）是否纳入 M11 还是更后——倾向 M11 先做单进程多 worker，跨进程留 M12。
- U-4：`StreamChunkJournal` 是否复用 `RuntimeStore.appendEvent`，还是独立 store（事件粒度 vs chunk 粒度不同）。
- U-5：provider fallback 是否需要 `ModelRef` 等价映射（同一 model 在不同 provider 的 model id 不同，如 `gpt-4` 在 OpenAI vs Azure）。
- U-6：cancellation 是否支持从外部系统（HTTP `DELETE /operations/{id}`）触发。
- U-7：turn journal 是否与现有 `saveSession`（turn 粒度）合并，避免双写。

## 9. 与其他组件的关系

| 组件 | 关系 |
| --- | --- |
| #1 streaming | stream chunk recovery 复用 #1 的 PromptStreamEvent；cancellation 中断 stream |
| #2 engine hardening | engine turn 事件触发 lease renew + TurnJournal append；tool validation 仍生效 |
| #5 authorizer | admission 阶段授权（async 模式不变） |
| #7 rate limiting | rate limit 在 admission（enqueue 前），durable queue 在 admission 后；二者正交 |
| #8 metrics/otel | WORKER_CLAIM_DURATION / LEASE_RECOVERY / PROVIDER_FALLBACK 指标；RECOVERING 事件入 trace |
| #11 api governance | WorkQueue/TurnJournal 是 core SPI（`core.store` 子包）；OperationStatus 扩展需 ErrorCode 对齐 |
| sandbox ✓ | RemoteSandbox 实现现有 Sandbox SPI；复用 SandboxContractTest |
| persistence-jdbc ✓ | JdbcWorkQueue / JdbcTurnJournal 实现进此模块 |
| runtime ✓ | durable 模式并入 AgentRuntime.Builder，`durable(false)` 向后兼容 |
