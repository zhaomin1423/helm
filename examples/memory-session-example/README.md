# Memory & Session Management Example

这个示例用一个客服助手场景验证 Helm 在生产环境所需的核心能力：

| 能力 | 验证方式 |
| --- | --- |
| 长期记忆（Memory 管理） | 模型通过内置 `save_memory` tool 存储用户偏好；新 session 的 instructions 自动注入已召回的记忆 |
| 持久化 Session（Session 管理） | 同一 session 多轮对话恢复历史；`listSessions` / `getSession` / `resetSession` 管理生命周期 |
| 上下文控制 | `maxSessionMessages` 限制发送给模型和持久化的历史长度 |
| Typed tools | `order_status` tool 使用 Java record 定义输入输出并自动生成 schema |
| 可观测操作 | 每次 prompt 产生可检查的 operation 记录 |

## 组成

- `SupportAgent`：启用 memory 的客服 agent，注册 `order_status` tool。
- `OrderStatusTool`：确定性的订单查询 tool。
- `MemorySessionExampleTest`：端到端场景测试，使用 `FakeProvider` 脚本驱动，无网络依赖。

## 场景流程

1. **Session `monday`**：用户说明偏好（express shipping）→ 模型调用 `save_memory` 持久化记忆。
2. **Session `monday` 第二轮**：会话历史从 store 恢复，模型调用 `order_status` 查询订单。
3. **Session `friday`**：全新 session，长期记忆自动注入 instructions，`save_memory` 与 `order_status` tool 均对模型可见。
4. **Session 管理**：列出全部 session、检查历史、重置 `monday` session；记忆归属 agent 实例，不随 session 删除。
5. **Operation 检查**：全部 3 次 prompt 的 operation 均为 `SUCCEEDED`。

## 运行

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -pl examples/memory-session-example -am test
```

生产部署时可将 `InMemoryMemoryStore` / `InMemoryRuntimeStore` 替换为 `helm-persistence-jdbc` 中的
`JdbcMemoryStore` / `JdbcRuntimeStore`，两者通过同一套契约测试保证行为一致。
