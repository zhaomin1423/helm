# 04 — Memory 语义检索

> 组件编号：4　　来源 milestone：post-M11 横向能力（`docs/roadmap.md` 第 3.1 节、第 4 节 M11 段）　　状态：proposed
>
> 依赖守则：本组件新增 SPI 放 `helm-core` 的 `core.memory` 子包；不引入 core 禁止依赖（runtime/engine/HTTP/CLI/Spring/provider SDK/JDBC/logging）。语义增强作为可选装饰层叠加在现有 `MemoryStore` 之上，保持 `MemoryStore` 接口与既有合约不变。新增 SPI 配 `SemanticMemoryStoreContractTest`（test-jar）。

---

## 1. 背景与目标

Helm 已具备 long-term memory 能力（M5 后补齐，见 `docs/roadmap.md` 第 3.1 节"Memory 管理（长期记忆）"行）：`MemoryStore` SPI + `InMemoryMemoryStore`/`JdbcMemoryStore` + 内置 `save_memory` tool + prompt 时把 memory 注入 instructions。但召回路径只做关键字匹配，且注入路径**根本没走 search**——直接 `list` 全量灌进 instructions。

`docs/roadmap.md` 第 3.1 节末尾明确："向量化语义记忆检索（当前 `MemoryStore.search` 为关键字匹配，SPI 已预留替换点）"列为后续生产能力，归入 M11 后或横向 P2 切片。

本组件在不破坏现有 `MemoryStore` SPI 与 `MemoryStoreContractTest` 的前提下，把召回升级为"语义 top-K 检索 + 关键字回退"。

**目标**

1. 新增 `EmbeddingProvider` SPI（`helm-core/core.memory`）：把文本转向量，可由 provider adapter（OpenAI embedding）实现。
2. 新增 `EmbeddingStore` SPI（`helm-core/core.memory`）：按 scope 存取向量并做 top-K 相似度检索。
3. 新增 `SemanticMemoryStore` 装饰器：组合 `MemoryStore` + `EmbeddingProvider` + `EmbeddingStore`，save 时 embed+存向量，search 时 embed query+cosine 相似度。
4. `AgentRuntime` 注入路径从"全量 list"升级为可配置策略：`FULL_LIST`（默认，向后兼容）或 `SEMANTIC_TOP_K`。
5. 相似度用 cosine；内存实现线性扫描，JDBC 用 pgvector（展望），外部向量库展望。
6. `MemoryStore` 接口零变更；`MemoryStoreContractTest` 不动；新增 `SemanticMemoryStoreContractTest` + `FakeEmbeddingProvider`（确定性 hash 向量，不依赖网络）。

**非目标**

- 不替换 `MemoryStore` 接口；不修改 `MemoryRecord` 必填字段（向量作为旁路存储，不进 record）。
- 不在本组件实现真实 provider embedding HTTP 调用（属于 provider adapter 范畴，本组件只定义 SPI 与契约）。
- 不引入向量库 SDK 进 core（独立 `helm-memory-semantic` 模块或并入 persistence adapter，见第 3.4 节决策）。
- 不实现 hybrid retrieval（BM25 + vector fusion）；仅做"语义优先，关键字回退"两段式。
- 不改 `save_memory` tool 的对外契约（仍只接收 subject + content）。

---

## 2. 现状与缺口

### 2.1 当前 `MemoryStore` SPI

`helm-core/src/main/java/io/agent/helm/core/memory/MemoryStore.java:10`：

```java
public interface MemoryStore {
    void save(MemoryRecord memory);
    Optional<MemoryRecord> load(String memoryId);
    List<MemoryRecord> list(String scopeId);          // 全量
    List<MemoryRecord> search(String scopeId, String query);  // 关键字
    void delete(String memoryId);
}
```

`MemoryStore.search` 的 Javadoc（`MemoryStore.java:18-22`）明确定义："Returns memories in the scope whose subject or content contains `query` (case-insensitive)"。

### 2.2 当前实现

| 实现 | 文件 | search 行为 |
| --- | --- | --- |
| In-memory | `helm-runtime/src/main/java/io/agent/helm/runtime/memory/InMemoryMemoryStore.java:35-41` | `subject.toLowerCase().contains(needle) || content.toLowerCase().contains(needle)` |
| JDBC | `helm-persistence-jdbc/src/main/java/io/agent/helm/persistence/jdbc/JdbcMemoryStore.java:60-69` | `LOWER(subject) LIKE ? OR LOWER(content) LIKE ?` |

两者都是 case-insensitive substring 关键字匹配，无向量化、无相似度、无语义。

### 2.3 注入路径缺口（关键）

`AgentRuntime.instructionsWithMemories`（`helm-runtime/src/main/java/io/agent/helm/runtime/AgentRuntime.java:269-287`）：

```java
private String instructionsWithMemories(String instructions, String scopeId) {
    if (memoryStore == null) {
        return instructions;
    }
    List<MemoryRecord> memories = memoryStore.list(scopeId);   // ← 全量 list，不是 search
    if (memories.isEmpty()) {
        return instructions;
    }
    StringBuilder builder = new StringBuilder(instructions);
    builder.append("\n\nKnown long-term memories:");
    for (MemoryRecord memory : memories) {
        builder.append("\n- ");
        if (!memory.subject().isBlank()) {
            builder.append('[').append(memory.subject()).append("] ");
        }
        builder.append(memory.content());
    }
    return builder.toString();
}
```

缺口：

1. **注入走 `list(scopeId)`，不是 `search`**——意味着 `MemoryStore.search` 现在根本没有运行时调用方（只有合约测试和 `JdbcMemoryStore` 自身实现）。即便我们把 `search` 升级为语义检索，注入路径也不会受益，必须同时改造 `instructionsWithMemories`。
2. **全量注入**：当 scope 下 memory 数量增长，instructions 会被全量 memory 撑爆 context window。`maxSessionMessages` 控制的是历史消息，不是 memory 注入量。
3. **无相关性排序**：所有 memory 等权注入，模型无法区分当前 prompt 相关的 memory。
4. `memoryScopeId`（`AgentRuntime.java:369-371`）是 `agentName + ":" + instanceId`，与 session 无关——memory 跨 session 共享是正确语义，本组件保留。

### 2.4 roadmap 出处

来源：`docs/roadmap.md:84`（第 3.1 节末尾）：

> 仍留待后续 milestone 的生产能力：……向量化语义记忆检索（当前 `MemoryStore.search` 为关键字匹配，SPI 已预留替换点）。

M11 段（`docs/roadmap.md:103`、`:400-419`）覆盖 async workers、queue、lease/recovery、remote sandbox 等 durable scale 能力，但**未显式列入向量化记忆**——本组件作为 M11 之后的横向切片或独立 P2 里程碑推进。

### 2.5 现有合约基线

`helm-core/src/test/java/io/agent/helm/core/memory/MemoryStoreContractTest.java` 已定义 7 个合约测试（saveAndLoad、loadMissing、listSorted、listUnknownScope、searchCaseInsensitive、saveReplaces、deleteRemoves）。`InMemoryMemoryStoreContractTest` 与 `JdbcMemoryStoreContractTest` 均通过。本组件**不能破坏这些测试**。

---

## 3. 设计方案

### 3.1 新增 SPI：`EmbeddingProvider`

放 `helm-core/core/memory`（与 `MemoryStore` 同包，语义上一致；不放 `core.model` 因为 model 已特指 LLM chat completion，embedding 是不同模态）。

```java
package io.agent.helm.core.memory;

/**
 * SPI for embedding text into a fixed-dimensional vector space. Used by semantic memory retrieval.
 * Implementations are typically provider adapters (e.g. OpenAI embeddings). Calls are external:
 * credentials must be read server-side and must never enter events or logs.
 */
public interface EmbeddingProvider {
    /**
     * Embeds the given text into a vector. The dimensionality must be stable across calls for a
     * given provider instance so vectors stay comparable.
     */
    float[] embed(String text);

    /** Dimensionality of vectors produced by this provider. */
    int dimension();
}
```

设计要点：

- `dimension()` 显式暴露，让 `EmbeddingStore` 能校验向量长度一致，避免混用不同模型 embedding。
- 不暴露 model name、api key、timeout——这些属 provider adapter 配置，core SPI 只关心"文本进、向量出"。
- 不抛业务异常；adapter 把网络/credential 错误映射为 `HelmException` 三段式（沿用 `core.error` 既有体系）。

### 3.2 新增 SPI：`EmbeddingStore`

```java
package io.agent.helm.core.memory;

import java.util.List;

/**
 * SPI for storing and retrieving memory embeddings by scope. Implementations may use in-memory
 * linear scan, pgvector, or an external vector database. Must not embed text itself; it only
 * operates on pre-computed vectors.
 */
public interface EmbeddingStore {
    /** Stores the embedding for a memory. Replaces any prior vector for the same memory id. */
    void store(String memoryId, float[] vector);

    /** Searches memories in {@code scopeId} by cosine similarity to {@code queryVector}, returning at most {@code topK}. */
    List<MemoryRecord> search(String scopeId, float[] queryVector, int topK);

    /** Removes the embedding for a memory. No-op if absent. */
    void delete(String memoryId);
}
```

设计要点：

- `EmbeddingStore` 只操作向量，不调用 `EmbeddingProvider`——职责分离，便于用 FakeEmbeddingProvider 在测试中注入确定性向量。
- `search` 返回 `MemoryRecord`（而非裸 id + score），让 `SemanticMemoryStore` 直接组合；调用方不需要知道 score。
- 相似度算法固定为 cosine，由实现内部完成（内存线性扫描 / pgvector `<=>` / 外部库）。

### 3.3 `SemanticMemoryStore` 装饰器

```java
package io.agent.helm.runtime.memory;

import io.agent.helm.core.memory.EmbeddingProvider;
import io.agent.helm.core.memory.EmbeddingStore;
import io.agent.helm.core.memory.MemoryRecord;
import io.agent.helm.core.memory.MemoryStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorates a {@link MemoryStore} with semantic retrieval. {@code save} persists the record via
 * the delegate and additionally embeds+stores the vector. {@code search} embeds the query and
 * delegates to {@link EmbeddingStore}; falls back to keyword {@link MemoryStore#search} when no
 * embedding provider is wired or the embedding store returns empty.
 */
public final class SemanticMemoryStore implements MemoryStore {
    private final MemoryStore delegate;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingStore embeddingStore;

    public SemanticMemoryStore(
            MemoryStore delegate,
            EmbeddingProvider embeddingProvider,
            EmbeddingStore embeddingStore) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.embeddingProvider = embeddingProvider;   // nullable: when null, search falls back to keyword
        this.embeddingStore = embeddingStore;          // nullable
    }

    @Override
    public void save(MemoryRecord memory) {
        delegate.save(memory);
        if (embeddingProvider != null && embeddingStore != null) {
            String text = memory.subject().isBlank()
                    ? memory.content()
                    : memory.subject() + " " + memory.content();
            float[] vector = embeddingProvider.embed(text);
            embeddingStore.store(memory.id(), vector);
        }
    }

    @Override
    public Optional<MemoryRecord> load(String memoryId) {
        return delegate.load(memoryId);
    }

    @Override
    public List<MemoryRecord> list(String scopeId) {
        return delegate.list(scopeId);
    }

    @Override
    public List<MemoryRecord> search(String scopeId, String query) {
        if (embeddingProvider == null || embeddingStore == null) {
            return delegate.search(scopeId, query);   // keyword fallback
        }
        float[] queryVector = embeddingProvider.embed(query);
        List<MemoryRecord> semantic = embeddingStore.search(scopeId, queryVector, Integer.MAX_VALUE);
        if (semantic.isEmpty()) {
            return delegate.search(scopeId, query);   // no vectors stored yet → keyword fallback
        }
        return semantic;
    }

    @Override
    public void delete(String memoryId) {
        delegate.delete(memoryId);
        if (embeddingStore != null) {
            embeddingStore.delete(memoryId);
        }
    }
}
```

设计要点：

- **装饰器而非新接口**：`SemanticMemoryStore implements MemoryStore`——任何接收 `MemoryStore` 的地方（`AgentRuntime.Builder.memoryStore`、`SaveMemoryTool`）零改造即可受益。
- **向后兼容**：不传 `EmbeddingProvider`/`EmbeddingStore` 时退化为纯关键字 store，行为等同 delegate。
- **save 双写**：record 走 delegate（保证 `MemoryStoreContractTest` 通过），向量走 `EmbeddingStore`。两路存储可能短暂不一致（delegate 成功、embedding 失败），见第 5.4 节权衡。
- **search 回退**：embedding store 为空时回退关键字，覆盖"既有 memory 未迁移向量化"的过渡期。

### 3.4 模块归属决策

新增 `helm-memory-semantic` 模块，**不并入 persistence adapter**。

| 选项 | 优点 | 缺点 | 决策 |
| --- | --- | --- | --- |
| 新增 `helm-memory-semantic` | 依赖隔离：core 不依赖向量库；语义增强可选；可在不引 JDBC 的环境用 | 多一个模块 | **采纳** |
| 并入 `helm-persistence-jdbc` | 模块少 | JDBC 用户被强制引入 embedding 概念；非 JDBC 环境（纯 InMemoryMemoryStore）无法用语义 | 否决 |
| 并入 `helm-runtime` | runtime 直接持有 | runtime 依赖向量库/SDK，违反"runtime 不引 SDK" | 否决 |
| 放 `helm-core` | SPI 触手可及 | core 依赖向量库 | 违反 core-first，否决 |

模块结构：

```text
helm-memory-semantic/
  src/main/java/io/agent/helm/memory/semantic/
    InMemoryEmbeddingStore.java          # 线性扫描 + cosine
    SemanticMemoryStore.java             # 第 3.3 节
    OpenAiEmbeddingProviderAdapter.java   # 展望：调 OpenAI embeddings API
  src/test/java/io/agent/helm/memory/semantic/
    InMemoryEmbeddingStoreTest.java
    SemanticMemoryStoreContractTest.java # 继承 helm-core test-jar 基类
    FakeEmbeddingProvider.java            # 确定性 hash 向量，供测试用
```

`SemanticMemoryStore` 放 `helm-memory-semantic` 而非 `helm-runtime`，避免 runtime 持有 embedding 概念；`AgentRuntime.Builder.memoryStore(SemanticMemoryStore)` 仍按 `MemoryStore` 接收，无类型耦合。

依赖关系：

```text
helm-memory-semantic → helm-core  (SPI)
                    → (可选) helm-provider-openai  (adapter 复用 HTTP/credential)
helm-runtime         → helm-core  (不变)
helm-persistence-jdbc → helm-core  (不变；pgvector 展望另开 helm-persistence-pgvector)
```

### 3.5 配置扩展

`AgentRuntime.Builder` 增加四个方法（`AgentRuntime.java:379-425` 现有 Builder 内）：

```java
public static final class Builder {
    // ... existing fields ...
    private EmbeddingProvider embeddingProvider;
    private EmbeddingStore embeddingStore;
    private MemoryRetrievalStrategy memoryRetrievalStrategy = MemoryRetrievalStrategy.FULL_LIST;
    private int memoryTopK = 5;

    public Builder embeddingProvider(EmbeddingProvider embeddingProvider) {
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        return this;
    }

    public Builder embeddingStore(EmbeddingStore embeddingStore) {
        this.embeddingStore = Objects.requireNonNull(embeddingStore, "embeddingStore");
        return this;
    }

    public Builder memoryRetrievalStrategy(MemoryRetrievalStrategy strategy) {
        this.memoryRetrievalStrategy = Objects.requireNonNull(strategy, "strategy");
        return this;
    }

    public Builder memoryTopK(int memoryTopK) {
        if (memoryTopK <= 0) {
            throw new IllegalArgumentException("memoryTopK must be positive");
        }
        this.memoryTopK = memoryTopK;
        return this;
    }

    // build() 自动用 SemanticMemoryStore 包装 memoryStore when embeddingProvider != null
}
```

新增 enum（`helm-core/core/memory`）：

```java
package io.agent.helm.core.memory;

public enum MemoryRetrievalStrategy {
    /** Inject all memories via list(scopeId). Backward compatible default. */
    FULL_LIST,
    /** Embed the last user message and inject only top-K most similar memories. */
    SEMANTIC_TOP_K
}
```

`build()` 自动包装逻辑：

```java
public AgentRuntime build() {
    MemoryStore effectiveMemoryStore = memoryStore;
    if (memoryStore != null && embeddingProvider != null && embeddingStore != null) {
        effectiveMemoryStore = new SemanticMemoryStore(memoryStore, embeddingProvider, embeddingStore);
    } else if ((embeddingProvider != null) != (embeddingStore != null)) {
        throw new IllegalStateException(
                "embeddingProvider and embeddingStore must be configured together");
    }
    return new AgentRuntime(
            agents, providers, store, effectiveMemoryStore, maxSessionMessages,
            memoryRetrievalStrategy, memoryTopK);
}
```

### 3.6 注入路径改造

`AgentRuntime.instructionsWithMemories`（`AgentRuntime.java:269-287`）签名扩展，新增 strategy + lastUserMessage + topK 参数：

```java
private String instructionsWithMemories(
        String instructions,
        String scopeId,
        MemoryRetrievalStrategy strategy,
        int topK,
        String lastUserMessage) {
    if (memoryStore == null) {
        return instructions;
    }
    List<MemoryRecord> memories;
    if (strategy == MemoryRetrievalStrategy.SEMANTIC_TOP_K && lastUserMessage != null && !lastUserMessage.isBlank()) {
        // SemanticMemoryStore.search embeds the query and falls back to keyword when no vectors.
        memories = memoryStore.search(scopeId, lastUserMessage);
        if (memories.size() > topK) {
            memories = memories.subList(0, topK);
        }
    } else {
        memories = memoryStore.list(scopeId);   // FULL_LIST, backward compatible
    }
    if (memories.isEmpty()) {
        return instructions;
    }
    StringBuilder builder = new StringBuilder(instructions);
    builder.append("\n\nKnown long-term memories:");
    for (MemoryRecord memory : memories) {
        builder.append("\n- ");
        if (!memory.subject().isBlank()) {
            builder.append('[').append(memory.subject()).append("] ");
        }
        builder.append(memory.content());
    }
    return builder.toString();
}
```

调用点（`AgentRuntime.java:183`）传入最后一条 user message：

```java
String lastUserMessage = request.text();
String instructions = instructionsWithMemories(
        config.instructions(), scopeId, memoryRetrievalStrategy, memoryTopK, lastUserMessage);
```

策略切换矩阵：

| `memoryRetrievalStrategy` | `embeddingProvider` 配置 | 行为 |
| --- | --- | --- |
| `FULL_LIST`（默认） | 否 | 现状：`list(scopeId)` 全量注入 |
| `FULL_LIST` | 是 | `SemanticMemoryStore.save` 仍写向量，但注入走 list（兼容期，向量准备好但未启用） |
| `SEMANTIC_TOP_K` | 否 | `search` 走 delegate 关键字匹配，截断 topK |
| `SEMANTIC_TOP_K` | 是 | `SemanticMemoryStore.search` 语义检索，截断 topK，无向量时回退关键字 |

### 3.7 向量存储展望

**In-memory**（`helm-memory-semantic/InMemoryEmbeddingStore`）：`ConcurrentMap<String, MemoryRecord+vector>` 线性扫描 + cosine。用于开发与测试。

```java
public final class InMemoryEmbeddingStore implements EmbeddingStore {
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private record Entry(String scopeId, MemoryRecord record, float[] vector) {}

    @Override
    public void store(String memoryId, float[] vector) { /* upsert by memoryId */ }

    @Override
    public List<MemoryRecord> search(String scopeId, float[] queryVector, int topK) {
        record Score(MemoryRecord r, double s) {}
        return entries.values().stream()
                .filter(e -> e.scopeId().equals(scopeId))
                .map(e -> new Score(e.record(), cosine(e.vector(), queryVector)))
                .sorted((a, b) -> Double.compare(b.s(), a.s()))
                .limit(topK)
                .map(Score::r)
                .toList();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0 : dot / denom;
    }
}
```

注意：`InMemoryEmbeddingStore` 需要在 `store` 时知道 `scopeId` 和 `MemoryRecord`——SPI 签名 `store(String memoryId, float[] vector)` 不够。两种方案：

- 方案 A（推荐）：`EmbeddingStore.store` 接收完整 `MemoryRecord`：`void store(MemoryRecord memory, float[] vector)`，避免 store 二次查 `MemoryStore`。
- 方案 B：`SemanticMemoryStore` 内部维护 `memoryId → scopeId` 映射，但与 delegate 可能不一致。

**采纳方案 A**，第 3.2 节 SPI 修订为：

```java
void store(MemoryRecord memory, float[] vector);
```

**JDBC + pgvector 展望**（不在本组件实现，仅给展望）：新增 `helm-persistence-pgvector` 模块或 `JdbcMemoryStore` 加 vector 列。`V3__memory_vectors.sql` 展望：

```sql
-- PostgreSQL + pgvector
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE helm_memory ADD COLUMN embedding vector(1536);
CREATE INDEX idx_helm_memory_embedding
    ON helm_memory USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
```

`EmbeddingStore.search` SQL 展望：

```sql
SELECT id, scope_id, subject, content, created_at
FROM helm_memory
WHERE scope_id = ?
ORDER BY embedding <=> ?   -- cosine distance
LIMIT ?
```

H2 不支持 pgvector，所以 JDBC 语义检索的合约测试仅跑 PostgreSQL baseline（M2 已建立"H2 测试 + PostgreSQL baseline"模式）。H2 环境降级为关键字回退。

**外部向量库展望**：Pinecone、Weaviate、Qdrant 等可通过实现 `EmbeddingStore` SPI 接入，core/runtime 不感知。

---

## 4. 数据流与时序

### 4.1 Save 流程（`save_memory` tool 触发）

```text
Model → ToolExecutor → SaveMemoryTool.execute
  → MemoryStore.save(MemoryRecord)
       ↓ SemanticMemoryStore.save
       ├─ delegate.save(memory)                    [InMemoryMemoryStore / JdbcMemoryStore]
       └─ if embeddingProvider != null:
            text = subject + " " + content
            vector = embeddingProvider.embed(text)  [外部调用]
            embeddingStore.store(memory, vector)    [InMemoryEmbeddingStore / pgvector]
```

时序要点：

- `delegate.save` 成功后再 `embed`，避免 record 持久化失败时还白算 embedding。
- `embed` 是外部调用，可能失败；失败时 record 已存，向量缺失——下次 `search` 自动回退关键字（见 3.3 search 回退逻辑）。
- `SaveMemoryTool` 对外契约不变：仍只返回 `memoryId`，不暴露 vector。

### 4.2 Prompt 注入流程

```text
AgentRuntime.prompt(request)
  → executePrompt
       → instructionsWithMemories(instructions, scopeId, strategy, topK, lastUserMessage)
            if strategy == SEMANTIC_TOP_K:
                memories = memoryStore.search(scopeId, lastUserMessage)   [SemanticMemoryStore.search]
                    ↓ embed(query) → queryVector
                    ↓ embeddingStore.search(scopeId, queryVector, MAX)   [cosine top-N]
                    ↓ if empty → delegate.search(scopeId, query)         [keyword fallback]
                memories = memories[0..topK]
            else: // FULL_LIST
                memories = memoryStore.list(scopeId)                     [现状不变]
       → AgentEngine.run(instructions + memories, messages, ...)
```

时序要点：

- 每个 prompt 触发一次 `embed(query)` 外部调用——增加一次模型调用延迟（OpenAI embedding ~100ms）。生产环境可缓存最近 query 的 vector，本组件不实现缓存（属 M9 observability 后的优化）。
- `SEMANTIC_TOP_K` 下注入量受 `memoryTopK` 控制（默认 5），不再受 memory 总数影响。
- 注入的 memory 不再"全量灌满 context"，降低 context overflow 风险（呼应 M3 context control）。

### 4.3 Delete 流程

```text
MemoryStore.delete(memoryId)
  ↓ SemanticMemoryStore.delete
  ├─ delegate.delete(memoryId)
  └─ embeddingStore.delete(memoryId)   [清理向量]
```

---

## 5. 安全与边界

### 5.1 Embedding 内容不进 events / logs

- `EmbeddingProvider.embed(text)` 的 `text` 来自 memory content 或 user query，可能含 PII。
- `RuntimeEventRecord` payload 由 `EventRedactor.redact`（`AgentRuntime.java:348`）处理；embedding 相关 payload **不记录**——`SemanticMemoryStore` 内部不触发事件，事件由 `AgentRuntime.appendEvent` 控制，仅记 `OPERATION_STARTED`/`OPERATION_SUCCEEDED` 元数据。
- `EmbeddingStore.search` 返回 `MemoryRecord`，score 不外泄到事件。
- 如果未来 logging observer 要记 embedding 调用，必须遵守 M9 redaction policy：只记 `{provider, dimension, durationMs}`，不记 `text` 或 `vector`。

### 5.2 Credential 服务端读取

- `EmbeddingProvider` 实现属外部调用，沿用 M4 真实 provider 的 credential 策略：API key 从环境变量 / secret manager 读取，不进 events/logs/safe errors。
- `OpenAiEmbeddingProviderAdapter` 展望复用 `helm-provider-openai` 的 HTTP client 与 credential provider，避免重复实现。
- 异常映射：网络/credential 错误映射为 `HelmException`，`details` 只含 `{provider, model, httpStatus}`，`developerDetails` 含 `{message}`，**不含** api key。

### 5.3 关键字回退边界

回退触发条件：

1. `embeddingProvider == null || embeddingStore == null`：用户未配置语义增强，纯关键字。
2. `embeddingStore.search` 返回空：scope 下无向量（既有 memory 未迁移）。
3. `embeddingProvider.embed` 抛异常：`SemanticMemoryStore.search` 应捕获并回退关键字，**不**让 embedding 失败阻塞 prompt。建议实现：

```java
@Override
public List<MemoryRecord> search(String scopeId, String query) {
    if (embeddingProvider == null || embeddingStore == null) {
        return delegate.search(scopeId, query);
    }
    try {
        float[] queryVector = embeddingProvider.embed(query);
        List<MemoryRecord> semantic = embeddingStore.search(scopeId, queryVector, Integer.MAX_VALUE);
        if (semantic.isEmpty()) {
            return delegate.search(scopeId, query);
        }
        return semantic;
    } catch (RuntimeException e) {
        // Embedding failure must not break prompt; fall back to keyword search.
        return delegate.search(scopeId, query);
    }
}
```

回退要记日志（M9 logging observer）但**不**抛错。

### 5.4 向量持久化隐私

- `JdbcMemoryStore` 加 vector 列（V3 迁移展望）后，向量与原文同表存储——向量本身是原文的数学投影，**不算脱敏**。如果原文含 PII，向量也可能被反向重建（embedding inversion attack）。
- 边界决策：向量与 record 同库同表，依赖库本身访问控制；不引入向量级加密（属部署侧责任）。
- 备份/导出：向量列在 `helm_memory` 表内，与原文同等敏感；M10 release engineering 文档需提示运维"向量列不能比原文更宽松地导出"。

### 5.5 双写一致性权衡

`SemanticMemoryStore.save` 是两阶段：delegate.save + embeddingStore.store。可能：

- delegate 成功、embedding 失败 → record 存在但无向量，search 回退关键字。**可接受**。
- delegate 失败、不调用 embedding → 无悬挂向量。**正确**。

不引入分布式事务（core 不依赖事务管理器）。如果未来要求强一致，可在 `helm-persistence-jdbc` 内实现"record + vector 同事务"的 `JdbcSemanticMemoryStore`，但属后续优化。

### 5.6 维度一致性

`EmbeddingProvider.dimension()` 与 `EmbeddingStore` 期望维度必须一致。`SemanticMemoryStore` 构造时校验：

```java
public SemanticMemoryStore(MemoryStore delegate, EmbeddingProvider ep, EmbeddingStore es) {
    // ...
    if (ep.dimension() != es.dimension()) {
        throw new IllegalArgumentException(
                "EmbeddingProvider dimension " + ep.dimension()
                + " does not match EmbeddingStore dimension " + es.dimension());
    }
}
```

避免混用不同 embedding 模型导致 cosine 失真。`EmbeddingStore` SPI 增 `int dimension()` 方法。

---

## 6. 测试策略

### 6.1 新增 `SemanticMemoryStoreContractTest`

放 `helm-core` test-jar（`src/test/java/io/agent/helm/core/memory/SemanticMemoryStoreContractTest.java`），随 helm-core test-jar 发布。子类实现 `createMemoryStore()` 返回 `SemanticMemoryStore`。

```java
package io.agent.helm.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Reusable contract tests for semantic memory retrieval. Adapters extend this class and supply
 * a {@link SemanticMemoryStore} wired with a {@link FakeEmbeddingProvider}. Tests are deterministic
 * and never touch the network.
 */
public abstract class SemanticMemoryStoreContractTest {

    protected abstract MemoryStore createStore();

    protected abstract EmbeddingProvider createEmbeddingProvider();

    private static final Instant T1 = Instant.ofEpochSecond(1000);
    private static final Instant T2 = Instant.ofEpochSecond(2000);

    @Test
    void searchReturnsSemanticallyRelatedMemoryBeforeKeywordMatch() {
        MemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "shipping", "Customer prefers express shipping", T1));
        store.save(new MemoryRecord("m2", "scope-a", "billing", "Invoice due in 30 days", T2));

        // Query "delivery speed preference" shares no keyword with m1 but should match semantically.
        List<MemoryRecord> result = store.search("scope-a", "delivery speed preference");

        assertThat(result).extracting(MemoryRecord::id).contains("m1");
    }

    @Test
    void searchFallsBackToKeywordWhenNoVectorsStored() {
        MemoryStore store = createStore();
        // Save via delegate directly (bypasses SemanticMemoryStore.save → no vector stored).
        // In practice this simulates memories persisted before semantic retrieval was enabled.
        store.save(new MemoryRecord("m1", "scope-a", "language", "User prefers Java", T1));

        List<MemoryRecord> result = store.search("scope-a", "java");

        assertThat(result).extracting(MemoryRecord::id).containsExactly("m1");
    }

    @Test
    void searchFallsBackToKeywordWhenEmbeddingProviderThrows() {
        MemoryStore store = createStore();   // wired with a provider that throws
        store.save(new MemoryRecord("m1", "scope-a", "prefs", "User prefers Java", T1));

        List<MemoryRecord> result = store.search("scope-a", "java");

        assertThat(result).extracting(MemoryRecord::id).containsExactly("m1");
    }

    @Test
    void saveStoresVectorEnablingSemanticSearch() {
        MemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "shipping", "Customer prefers express shipping", T1));
        store.save(new MemoryRecord("m2", "scope-a", "billing", "Invoice due in 30 days", T2));

        // "fast delivery" shares no tokens with "express shipping" but FakeEmbeddingProvider
        // hashes related words to nearby vectors, so m1 ranks first.
        List<MemoryRecord> result = store.search("scope-a", "fast delivery");

        assertThat(result.get(0).id()).isEqualTo("m1");
    }

    @Test
    void deleteRemovesVectorAndRecord() {
        MemoryStore store = createStore();
        store.save(new MemoryRecord("m1", "scope-a", "prefs", "content", T1));
        store.delete("m1");

        assertThat(store.load("m1")).isEmpty();
        assertThat(store.search("scope-a", "content")).isEmpty();
    }
}
```

### 6.2 `FakeEmbeddingProvider`

放 `helm-memory-semantic` test 范围或 `helm-runtime-testkit`（让所有 adapter 共用）。**确定性**：相同输入产生相同向量，不依赖网络。

```java
package io.agent.helm.memory.semantic;

import io.agent.helm.core.memory.EmbeddingProvider;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic embedding provider for tests. Hashes token sets into a fixed-dimensional vector so
 * that semantically related text (sharing tokens or synonyms from a small built-in map) produces
 * nearby vectors. Never touches the network.
 */
public final class FakeEmbeddingProvider implements EmbeddingProvider {
    private static final int DIMENSION = 64;

    @Override
    public float[] embed(String text) {
        Set<String> tokens = tokenize(text);
        // Map each token to a deterministic bucket; sum into vector, then L2-normalize.
        float[] vector = new float[DIMENSION];
        for (String token : tokens) {
            int bucket = Math.floorMod(token.hashCode(), DIMENSION);
            vector[bucket] += 1.0f;
            // Synonym hint: "fast" ~ "express", "delivery" ~ "shipping"
            for (String synonym : synonyms(token)) {
                int synBucket = Math.floorMod(synonym.hashCode(), DIMENSION);
                vector[synBucket] += 0.5f;
            }
        }
        return normalize(vector);
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    private static Set<String> tokenize(String text) {
        return new HashSet<>(Arrays.asList(text.toLowerCase(Locale.ROOT).split("\\W+")));
    }

    private static String[] synonyms(String token) {
        return switch (token) {
            case "fast", "quick", "speed" -> new String[]{"express"};
            case "delivery" -> new String[]{"shipping"};
            case "preference", "prefers" -> new String[]{"prefers"};
            default -> new String[0];
        };
    }

    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }
}
```

### 6.3 与既有 `MemoryStoreContractTest` 关系

- **不修改** `MemoryStoreContractTest`（7 个测试保持原样）。
- `SemanticMemoryStore` 因为 `implements MemoryStore`，理论上也能继承 `MemoryStoreContractTest`——这是验证装饰器向后兼容的关键测试。`helm-memory-semantic` 应同时跑两套合约：

```java
public final class SemanticMemoryStoreContractTest
        extends io.agent.helm.core.memory.SemanticMemoryStoreContractTest { /* semantic-specific */ }

public final class SemanticMemoryStoreBackwardCompatTest
        extends io.agent.helm.core.memory.MemoryStoreContractTest { /* 7 existing tests */ }
```

如果 `SemanticMemoryStore` 不能通过 `MemoryStoreContractTest`，说明装饰器破坏了基础语义——这是 CI 红线。

### 6.4 `AgentRuntime` 注入策略测试

新增 `AgentRuntimeMemoryInjectionTest`（`helm-runtime` test）：

- `FULL_LIST` 策略下，所有 memory 进 instructions（与现状一致）。
- `SEMANTIC_TOP_K` 策略下，只有 top-K 进 instructions。
- 未配置 embeddingProvider 时，`SEMANTIC_TOP_K` 退化为关键字 search + topK 截断。
- 注入的 memory 不含向量、不含 score。

### 6.5 测试矩阵

| 测试 | 模块 | 范围 | 网络依赖 |
| --- | --- | --- | --- |
| `MemoryStoreContractTest`（既有） | helm-core test-jar | 7 基础语义 | 否 |
| `SemanticMemoryStoreContractTest` | helm-core test-jar | 5 语义语义 | 否 |
| `SemanticMemoryStoreBackwardCompatTest` | helm-memory-semantic | 继承上者 | 否 |
| `InMemoryEmbeddingStoreTest` | helm-memory-semantic | cosine 正确性 | 否 |
| `FakeEmbeddingProviderTest` | helm-memory-semantic | 确定性 + 维度 | 否 |
| `AgentRuntimeMemoryInjectionTest` | helm-runtime | 策略切换 | 否 |
| `OpenAiEmbeddingProviderAdapterContractTest`（展望） | helm-provider-openai | mock server | 否（mock） |

---

## 7. 验收标准

- [ ] `helm-core` 新增 `EmbeddingProvider`、`EmbeddingStore`、`MemoryRetrievalStrategy` 三个 SPI/enum，位于 `core.memory` 子包，core 无新增依赖。
- [ ] `helm-memory-semantic` 模块独立编译，依赖仅 `helm-core`。
- [ ] `SemanticMemoryStore` 通过既有 `MemoryStoreContractTest`（7 测试，零修改基类）。
- [ ] `SemanticMemoryStore` 通过新增 `SemanticMemoryStoreContractTest`（5 测试，含语义相关性 + 回退）。
- [ ] `FakeEmbeddingProvider` 确定性：相同输入跨运行产生相同向量。
- [ ] `AgentRuntime.Builder` 新增 4 个方法（embeddingProvider / embeddingStore / memoryRetrievalStrategy / memoryTopK），未配置时行为与现状完全一致。
- [ ] `injectionWithMemories` 在 `SEMANTIC_TOP_K` 下只注入 top-K memory；`FULL_LIST` 下注入全量。
- [ ] embedding 调用失败不阻塞 prompt（回退关键字）。
- [ ] `mvn verify` 全绿，新增模块纳入 reactor build。
- [ ] 不修改 `MemoryStore.java`、`MemoryRecord.java`、`SaveMemoryTool.java`、`V2__memory.sql`。
- [ ] `examples/memory-session-example` 仍通过（语义增强可选，不强制升级示例）。
- [ ] 文档：本设计文档 + 模块 README（如有）说明配置项与回退语义。

---

## 8. 风险与未决项

### 8.1 风险

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| 每个 prompt 增一次 embedding 外部调用，延迟翻倍 | HIGH | 短期：默认 `FULL_LIST`，语义检索 opt-in；中期：query vector LRU 缓存（M9 后） |
| `FakeEmbeddingProvider` 的"同义词"映射过于简化，合约测试可能脆弱 | MEDIUM | 测试用例选词明确（fast/express、delivery/shipping），不依赖模糊语义；真实语义验证留给 provider adapter 集成测试 |
| 双写不一致（record 存在、向量缺失）导致 search 回退时结果不完整 | LOW | 回退关键字是可接受降级；后续可加 `helm-persistence-jdbc` 同事务实现 |
| 向量维度变更（换 embedding 模型）导致既有向量失效 | MEDIUM | `dimension()` 校验拦截；migration 文档提示"换模型需重 embed 全量 memory" |
| embedding inversion attack：从向量重建原文 | LOW | 向量与原文同等敏感，依赖库访问控制；不引入向量级加密 |
| `JdbcMemoryStore` 加 vector 列与 pgvector 强耦合 | MEDIUM | pgvector 实现放独立模块 `helm-persistence-pgvector`（展望），`helm-persistence-jdbc` 保持纯关键字 |

### 8.2 未决项

1. **`EmbeddingStore.store` 签名**：采纳 `store(MemoryRecord, float[])`（含 scopeId）还是 `store(String memoryId, float[])` + 内部反查？本设计建议前者（方案 A），但需在实现时确认 `helm-persistence-jdbc` 同事务场景是否需要拆分。
2. **向量缓存**：query vector 是否在 `AgentRuntime` 内缓存？缓存键是 `(scopeId, lastUserMessage)` 还是 `(lastUserMessage)`？跨 scope 复用是否安全？留给实现切片决策。
3. **`memoryTopK` 默认值**：本设计取 5，需实测真实场景 instructions token 占用后调整。与 `maxSessionMessages`（`AgentRuntime.java:42` 默认 0=不裁剪）不同，`memoryTopK` 必须正整数。
4. **pgvector 模块归属**：新增 `helm-persistence-pgvector` 还是 `helm-persistence-jdbc` 加 optional profile？前者更干净，后者减少模块数。M2 已建立"H2 测试 + PostgreSQL baseline"模式，pgvector 需 PostgreSQL baseline 才能跑语义合约。
5. **`save_memory` tool 是否暴露 memory update**：当前 `save` 是 MERGE 语义（`JdbcMemoryStore.java:34`），update memory 时向量是否重新 embed？`SemanticMemoryStore.save` 已处理（每次 save 都重新 embed+store），但既有 `MemoryStoreContractTest.saveReplacesExistingMemory` 不验证向量替换——需在 `SemanticMemoryStoreContractTest` 补"update 后旧向量不污染 search"。
6. **多语言 embedding**：中英文 memory 混合时，OpenAI embedding 跨语言对齐良好，但 `FakeEmbeddingProvider` 的 token 分词只支持空白分隔——合约测试是否需要覆盖中文？建议否（FakeProvider 仅验证 SPI 串联，真实多语言验证留给 provider adapter）。

---

## 9. 与其他组件关系

| 组件 | 关系 | 说明 |
| --- | --- | --- |
| **persistence-jdbc**（M5 已完成） | 协作 | `JdbcMemoryStore` 保持不变；`V3__memory_vectors.sql` 展望加 vector 列，pgvector 实现可独立为 `helm-persistence-pgvector` |
| **provider**（M4 已完成） | 协作 | `OpenAiEmbeddingProviderAdapter` 展望复用 `helm-provider-openai` 的 HTTP client 与 credential provider；`EmbeddingProvider` SPI 与 `ModelProvider` 并列，互不依赖 |
| **memory / session**（M5 已完成） | 增强 | 本组件只增强 `MemoryStore.search` 与 `AgentRuntime.instructionsWithMemories`；`MemoryRecord`、`SaveMemoryTool`、`memoryScopeId`、session 管理全部不变 |
| **api governance #11**（proposed） | 约束 | 新增 SPI（`EmbeddingProvider`、`EmbeddingStore`、`MemoryRetrievalStrategy`）按 #11 的 package 三档分类：`core.memory` 是 SPI 包，public API 冻结受 japicmp baseline 保护；`SemanticMemoryStore` 在 `helm-memory-semantic` 是 internal；exception code 沿用 `core.error` 既有注册表 |
| **engine hardening #02**（M3） | 关联 | 语义检索降低 memory 注入量，间接缓解 context overflow（M3 的初始 context overflow 分类）；但 `SEMANTIC_TOP_K` 引入的外部 embedding 调用不在 engine 的 model turn 事件范畴，需在 `AgentRuntime` 层记 `MEMORY_RECALLED` 事件（展望，不属本组件强制） |
| **JsonSchema #03**（M3） | 无关 | embedding 不涉及 tool input schema |
| **observability #08**（M9） | 约束 | embedding 调用的 logging/metrics 必须遵守 M9 redaction policy：只记 `{provider, dimension, durationMs, success}`，不记 text/vector；新增 metric `helm.memory.semantic.search.duration`、`helm.memory.semantic.fallback.count` |
| **rate limiting #07**（proposed） | 关联 | embedding 调用属外部 API 调用，应纳入 rate limiter 计数（与 model 调用共享配额还是独立配额？未决，见 #07 设计） |
| **authorizer #05**（proposed） | 无关 | memory 注入不涉及用户授权决策；`save_memory` tool 的授权由 `HelmAuthorizer` 在 tool 层处理，与本组件无关 |

**模块依赖图（本组件引入后）**

```text
helm-core ──── no new deps
  └─ core.memory
       MemoryStore           (existing, unchanged)
       MemoryRecord          (existing, unchanged)
       EmbeddingProvider     (new SPI)
       EmbeddingStore        (new SPI)
       MemoryRetrievalStrategy (new enum)

helm-runtime ──── no new deps
  └─ AgentRuntime.Builder   (4 new methods)
  └─ AgentRuntime.injectionWithMemories  (strategy switch)

helm-memory-semantic (new) ──→ helm-core
  ├─ SemanticMemoryStore
  ├─ InMemoryEmbeddingStore
  ├─ FakeEmbeddingProvider (test)
  └─ OpenAiEmbeddingProviderAdapter (展望)

helm-persistence-jdbc ──── no new deps (V3 migration 展望另议)
helm-persistence-pgvector (展望) ──→ helm-core + pgvector driver
```

---

> **实现切片建议**：本组件可拆为两个切片——(1) SPI + `SemanticMemoryStore` + `InMemoryEmbeddingStore` + `FakeEmbeddingProvider` + 合约测试，不接真实 provider；(2) `OpenAiEmbeddingProviderAdapter` + mock-server 合约测试。切片 1 完成后即可让用户自接 `EmbeddingProvider` 实现，切片 2 属 provider adapter 工作。
