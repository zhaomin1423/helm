# Sandbox Adapter 实现指南

本指南说明如何为 Helm 实现新的 `Sandbox` SPI adapter，为模型提供受控的文件系统与 shell 执行边界。

## 1. SPI 概览

`Sandbox` 接口位于 `io.agent.helm.core.sandbox`，由两部分组成：

```java
public interface Sandbox {
    SandboxFileSystem fs();
    SandboxShell shell();
}
```

### 1.1 SandboxFileSystem

受控的、路径归一化的文件系统视图。所有路径相对于 sandbox root：

```java
public interface SandboxFileSystem {
    String readText(String path);
    void writeText(String path, String content);
    List<String> listFiles(String path);
    boolean exists(String path);
    void delete(String path);
}
```

### 1.2 SandboxShell

执行 shell 命令的受控接口：

```java
public interface SandboxShell {
    SandboxCommandResult execute(SandboxCommand command);
}
```

现有实现：`InMemorySandbox`、`LocalSandbox`（均位于 `helm-sandbox-local`）。

## 2. 路径安全合约

`SandboxFileSystem` 实现必须对路径做归一化，并拒绝逃逸 root 的路径：

- **拒绝绝对路径**：如 `/etc/passwd`。
- **拒绝 traversal 序列**：如 `../../etc/passwd`。
- **拒绝 symlink 逃逸**：symlink 指向 root 外的路径须拒绝。
- 所有路径相对于 sandbox root 解释。

参考 `LocalSandbox` 的实现：用 `Path.normalize()` + `Path.startsWith(root)` 校验。

```java
Path resolved = root.resolve(path).normalize();
if (!resolved.startsWith(root)) {
    throw new SandboxException("SANDBOX_ERROR", "path escapes sandbox root: " + path,
            /*details*/ Map.of("path", path), /*developerDetails*/ null);
}
```

## 3. Shell 默认关闭

`SandboxShell` 默认应处于**关闭**状态（Helm 安全默认原则）。`LocalSandbox` 的 shell 默认禁用，需显式配置 allowed commands 才启用。

实现新 sandbox 时：

- 默认 `execute` 抛 `SandboxException`（code = `SANDBOX_ERROR`），说明 shell 未启用。
- allowed env / allowed commands 须显式配置，不接受通配符。
- 命令超时、输出长度限制须可配置。

```java
@Override
public SandboxCommandResult execute(SandboxCommand command) {
    if (!allowedCommands.contains(command.command())) {
        throw new SandboxException("SANDBOX_ERROR", "shell disabled or command not allowed",
                /*details*/ Map.of("command", command.command()), /*developerDetails*/ null);
    }
    // ... 执行并返回 SandboxCommandResult
}
```

## 4. 合约测试

必须 extend `SandboxContractTest`（位于 `helm-core` test-jar）：

```java
package io.agent.helm.sandbox.example;

import io.agent.helm.core.sandbox.Sandbox;
import io.agent.helm.core.sandbox.SandboxContractTest;

class ExampleSandboxContractTest extends SandboxContractTest {
    @Override
    protected Sandbox createSandbox() {
        return new ExampleSandbox(/* config */);
    }
}
```

合约覆盖：路径归一化、traversal 拒绝、绝对路径拒绝、文件读写、shell 默认行为。

## 5. SANDBOX_ERROR 使用约束

所有 sandbox 失败映射为 `SandboxException`（code = `SANDBOX_ERROR`）：

| 场景 | details |
| --- | --- |
| 路径逃逸 | `path` |
| 命令未允许 | `command` |
| 命令超时 | `command`, `timeoutMillis` |
| 输出超限 | `command`, `outputLimit` |

`developerDetails` 可含 stack trace 或 stderr，不进 HTTP 响应与事件 payload。

## 6. LocalSandbox 不是生产隔离

`LocalSandbox` 直接在宿主文件系统与进程执行，**不是生产级隔离**。文档须明确：

- 本地开发：方便快速迭代。
- 生产隔离：依赖容器 / VM / 远程 sandbox adapter（组件 #9 durable scale 落地后提供）。

新 sandbox adapter 若面向生产，应明确隔离边界（容器、VM、远程服务），并在 README 中标注安全保证。

## 7. 依赖约束

sandbox adapter 只依赖 `helm-core`，不依赖 `helm-runtime` 或 `helm-agent-engine`。

## 8. 发布与 BOM 注册

模块完成后在 `helm-bom` 注册坐标。详见 [docs/design/10-release-engineering.md](../design/10-release-engineering.md) §3.2.3。
