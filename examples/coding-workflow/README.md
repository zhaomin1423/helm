# Coding Workflow Example

这个示例展示如何用 Helm 设计一个“软件开发自动化” workflow：从 GitHub issue 读取需求，生成设计方案，审查方案，开发代码，执行验证，进行代码审查，最后提交 pull request。

> 当前 Helm 还处于 MVP 设计阶段。本示例是基于 Helm 目标 API 的参考实现，用于固定使用场景、API 形态和安全边界；等 runtime 模块实现后，可演进为可运行示例。

## 场景

输入是一个 GitHub issue：

```json
{
  "owner": "acme",
  "repository": "billing-service",
  "issueNumber": 42,
  "baseBranch": "main",
  "openDraftPullRequest": true
}
```

Workflow 自动完成：

1. 从 GitHub issue 读取需求、评论和相关上下文。
2. 让 Agent 生成实现设计。
3. 让 Agent 审查设计，要求指出风险、遗漏和不可执行之处。
4. 创建工作分支。
5. 让 Agent 基于设计修改代码。
6. 执行项目验证命令。
7. 让 Agent 对 diff 做代码审查。
8. 根据审查结果修正代码并再次验证。
9. 创建 draft pull request，并把设计、验证和审查摘要写入 PR 描述。

## 为什么用 Workflow

这是一个有限软件交付任务：有明确输入、明确结束条件和明确产物，所以应该建模为 `WorkflowRun`，而不是长期 Agent 会话。

Workflow 负责可信编排：

- 决定从哪个 issue 读取需求。
- 决定分支名和目标 base branch。
- 控制是否创建 pull request。
- 控制验证命令。
- 控制最多允许几轮 review/fix。

Agent 负责智能工作：

- 理解需求。
- 生成设计。
- 发现风险。
- 修改代码。
- 解释验证失败。
- 审查 diff。

## 安全边界

GitHub token、仓库路径和 PR 创建权限都留在应用代码中。模型只在 workflow 绑定的 sandbox 工作区内读写文件；GitHub 读取、分支创建、验证、diff 和 PR 创建由 workflow 的可信代码调用。

| 边界 | 能力 |
| --- | --- |
| Workflow trusted code | 读取 GitHub issue、创建分支、运行验证、读取 diff、创建 PR。 |
| Agent sandbox | 读取当前工作区文件、编辑文件、根据验证结果修复代码。 |
| Skills | 约束设计、设计审查、实现和代码审查的执行方式。 |

模型不能选择任意 owner、repository、branch 或 GitHub token。Workflow 在开始时把 issue、仓库和分支绑定到本次 run，所有工具只在这个绑定上下文中工作。

## 文件结构

```text
examples/coding-workflow/
  README.md
  src/main/java/io/github/zhaomin/helm/examples/codingworkflow/
    CodingAgent.java
    CodingWorkflow.java
    CodingWorkflowInput.java
    CodingWorkflowOutput.java
    GitHubProjectTools.java
  skills/
    design/SKILL.md
    design-review/SKILL.md
    implementation/SKILL.md
    code-review/SKILL.md
```

## Workflow 设计

核心 workflow 位于 [`CodingWorkflow.java`](src/main/java/io/github/zhaomin/helm/examples/codingworkflow/CodingWorkflow.java)。

执行阶段：

| 阶段 | 输入 | 输出 |
| --- | --- | --- |
| `read-requirements` | GitHub issue id | 需求上下文 |
| `design` | 需求上下文 | 实现设计 |
| `design-review` | 需求 + 设计 | 设计审查报告 |
| `implement` | 需求 + 已审查设计 | 代码修改 |
| `verify` | 当前工作分支 | 验证结果 |
| `code-review` | diff + 验证结果 | 代码审查报告 |
| `open-pr` | issue + branch + 摘要 | draft PR |

## 目标 API 示例

```java
WorkflowRunHandle run = workflowRuntime.invoke(
    WorkflowInvokeRequest.of(
        "coding-workflow",
        new CodingWorkflowInput(
            "acme",
            "billing-service",
            42,
            "main",
            true
        )
    )
);
```

CLI 目标形态：

```bash
helm run coding-workflow --input '{
  "owner": "acme",
  "repository": "billing-service",
  "issueNumber": 42,
  "baseBranch": "main",
  "openDraftPullRequest": true
}'
```

## 输出

Workflow 输出：

```json
{
  "issueNumber": 42,
  "branchName": "helm/issue-42-billing-timeout",
  "pullRequestUrl": "https://github.com/acme/billing-service/pull/108",
  "designSummary": "Add timeout-aware retry handling around billing provider calls.",
  "verificationSummary": "Unit tests and integration smoke checks passed.",
  "reviewSummary": "No blocking issues found after one fix round."
}
```

## 失败处理

Workflow 应在这些情况失败，并保存结构化错误：

1. Issue 不存在或调用者没有权限读取。
2. 设计审查指出需求不可执行。
3. 工作分支创建失败。
4. Agent 修改代码后验证仍失败。
5. 代码审查发现阻塞问题且自动修复后仍未通过。
6. PR 创建失败。

失败时不应吞掉错误。Run record 应保留阶段、错误码、可安全展示的错误信息和 developer details。

## 后续实现要求

这个示例对 Helm runtime 提出这些能力要求：

1. Workflow 可以显式调用 skill。
2. Session 可以跨多个 operation 保留上下文。
3. Tool 可以绑定到 workflow run 的可信上下文。
4. Sandbox 支持读取、编辑文件和运行验证命令。
5. Runtime event 能记录每个阶段的开始、结束、tool call、模型 turn 和错误。
6. Workflow output 可以被 schema 校验并持久化。
