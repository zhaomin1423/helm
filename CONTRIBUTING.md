# Contributing to Helm

感谢你对 Helm 项目的关注！本文档涵盖开发流程、代码规范与测试要求。

## Getting started

### Prerequisites

- **JDK 21 (LTS)**：Helm 唯一支持的构建 JDK。
- **Maven**：可使用项目自带的 `./mvnw` wrapper（无需单独安装），或本地 Maven 3.9+。
- **Git**。

### 安装 JDK 21

```bash
# macOS
brew install openjdk@21
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH=$JAVA_HOME/bin:$PATH

# 验证
java -version
./mvnw -v
```

### Build and verify

```bash
./mvnw -B -ntp verify
```

该命令运行编译、单元测试与 Spotless 格式检查。

### Format

```bash
./mvnw -B -ntp spotless:apply
./mvnw -B -ntp spotless:check
```

Spotless 是格式化的唯一来源。永远不要手动格式化代码以对抗 Spotless 输出。
Java 使用 `palantirJavaFormat 2.50.0`（PALANTIR style），Markdown 也会被检查
（`trimTrailingWhitespace` + `endWithNewline`）。

## Development workflow

1. **Fork** 仓库并 clone 你的 fork。
2. **Branch**：从 `main` 创建 feature 分支：
   ```bash
   git checkout -b feat/my-feature
   ```
3. **Develop**：先写测试（推荐 TDD），再写实现。
4. **Format**：提交前运行 `./mvnw spotless:apply`。
5. **Verify**：本地运行 `./mvnw verify`。失败则修复后再推送。
6. **Commit**：使用 conventional commits（见下文）。
7. **Push** 并向 `main` 提交 pull request。
8. **CI**：GitHub Actions 运行 compile、spotless、tests、dependency scan。
9. **Review**：处理 reviewer 反馈。CRITICAL / HIGH 级别问题阻塞合并。
10. **Merge**：审批后 squash-and-merge。

## Commit message format

```
<type>: <description>

<optional body>
```

Types：`feat`、`fix`、`refactor`、`docs`、`test`、`chore`、`perf`、`ci`。

示例：

- `feat(provider): add streaming support to OpenAI adapter`
- `fix(jdbc): handle null session state on first load`
- `docs: update CHANGELOG for 0.2.0 release`

## Coding standards

- 保持 `helm-core` 不依赖 Spring、Servlet、CLI、provider SDK、JDBC 与 logging adapter
  （Core-first principle）。
- 偏好小接口（1-3 方法）与不可变 record / value object。
- 将框架失败包装为结构化 `HelmException` 子类。
- 新 SPI 必须配套 `ContractTest` 基类（当前在 `helm-core/src/test/java`，未来迁移到
  `helm-runtime-testkit`）。
- 新 `HelmException` code 必须在 `ErrorCode` enum 注册（见
  [docs/design/11-api-governance.md](docs/design/11-api-governance.md)）。

## Testing expectations

- 完成变更前必须运行 `./mvnw verify`。
- 使用 `FakeProvider`、`InMemoryRuntimeStore`、`InMemoryMemoryStore`、`InMemorySandbox`
  进行确定性测试。CI 中绝不引入真实 provider credential 或网络依赖测试。
- 新 SPI adapter 必须通过对应合约测试：
  - Provider：`extends ModelProviderContractTest`
  - Sandbox：`extends SandboxContractTest`
  - RuntimeStore：`extends RuntimeStoreContractTest`
  - MemoryStore：`extends MemoryStoreContractTest`
- 若因 JDK 21 缺失无法验证，须明确报告，不得声称已验证。

## License

By contributing, you agree your contributions are licensed under the Apache
License, Version 2.0 (see [LICENSE](LICENSE)).
