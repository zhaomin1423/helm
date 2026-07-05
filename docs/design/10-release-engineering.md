# 10. Release Engineering

Helm 在系统设计 Milestone 1–5 与 2026-07-05 的 Memory/Session 补齐后，已具备完整 MVP 基座（core/engine/runtime + 8 个 adapter 模块 + 3 个 examples）。但要让外部 Java 开发者真正采用，必须把发布工程闭环做齐。本组件不是新增功能能力，而是为整个项目补上 license、CI、publishing、文档、示例的"可被外部消费"基线。

---

## 1. 背景与目标

### 1.1 为什么需要 release engineering

Helm 当前是 12 个核心/adapter 模块 + 3 个 examples 的 Maven 多模块仓库，但所有"对外发布"基础设施都是空白：

- 没有 license 决策（`README.md` 第 172 行明示 "License 尚未确定"）。
- 没有 `CHANGELOG.md` / `CONTRIBUTING.md`，外部贡献者无流程可循。
- 没有 Maven wrapper，外部 contributor 必须自行安装 Maven 才能构建。
- 没有 CI，`mvn verify` 全靠开发者本地手动执行。
- 没有 publishing checklist，发布到 Maven Central 无标准流程。
- 没有模块级 README，外部用户看不出每个 jar 用途。
- 没有 adapter 实现指南，外部想新增一个 provider / persistence / sandbox / observer adapter 无从下手。
- examples 不完整：缺 FakeProvider standalone、real provider、JDBC、HTTP、sandbox、typed tools、streaming 等独立可运行示例。
- 没有独立外部 sample project，无法验证已发布 artifacts 真正可被消费。

### 1.2 本组件目标

| 目标 | 内容 |
| --- | --- |
| License 决策 | 推荐 Apache License 2.0，给出 LICENSE 文件与源文件 header 策略。 |
| Maven 坐标对齐 | 落地 `io.agent.helm` groupId（与 API governance #11 §3.4 决策一致），新增 `helm-bom` 统一版本。 |
| Maven wrapper | 引入 `mvnw` / `mvnw.cmd` / `.mvn/wrapper/`，固定 JDK 21 要求。 |
| CI（GitHub Actions） | 设计 `ci.yml`：compile、test、spotless check、dependency 漏洞扫描、javadoc generation，JDK 21 matrix。 |
| Maven publishing checklist | Sonatype Central Portal 流程、GPG 签名、sources/javadoc jar、staging release、版本号策略。 |
| `helm-runtime-testkit` 模块 | 把 `helm-core/src/test/java` 下散落的 4 个 ContractTest 基类集中到独立 testkit 模块，发布 test-jar 给外部 adapter 复用。 |
| `CHANGELOG.md` | Keep a Changelog 格式模板。 |
| `CONTRIBUTING.md` | fork→branch→PR→CI→review 流程、Spotless 规范、conventional commits、测试要求。 |
| 模块级 READMEs | 每个 jar 一个 README 模板（用途、依赖、API 速览、示例、测试运行）。 |
| Adapter 实现指南 | provider / persistence / sandbox / observability / spring 各一篇。 |
| Runnable examples 矩阵 | 补缺 FakeProvider standalone、real provider、JDBC、HTTP、sandbox、typed tools、streaming。 |
| Clean consumer sample | 独立外部 sample project，消费已发布 Maven artifacts，验证可被外部采用。 |

### 1.3 不解决什么

- 不重新设计模块依赖结构（属 API governance #11）。
- 不引入新 SPI 或功能能力（属各组件）。
- 不实现 JPMS 模块化（属 #11 §3.7 阶段 2）。
- 不规定 API 兼容性策略（属 #11 §3.2）。
- 不替换 Spotless / JUnit 5 / Flyway 等既有技术选型。

---

## 2. 现状与缺口

### 2.1 roadmap M10 未完成项

来源：`docs/roadmap.md` 第 5 节 M10 交付清单（全部未勾选）。

| M10 交付项 | 现状 | 本文档解决位置 |
| --- | --- | --- |
| `CHANGELOG.md` | 无 | §3.7 |
| `CONTRIBUTING.md` | 无 | §3.8 |
| license 决策 | `README.md` 明示未确定 | §3.1 |
| Maven wrapper | 无 `mvnw` / `.mvn/wrapper/` | §3.3 |
| CI：compile、tests、Spotless、dependency checks、Javadocs | 无 `.github/workflows/` | §3.4 |
| Maven publishing checklist | 无 | §3.5 |
| clean consumer sample | 无 | §3.11 |
| package-specific READMEs | 仅 `examples/coding-workflow/README.md` 等少数 | §3.9 |
| adapter implementation guides | 无 | §3.10 |
| runnable examples 矩阵 | 仅 3 个 examples | §3.11 |

### 2.2 roadmap 第 7 节阻塞项

来源：`docs/roadmap.md` 第 7 节"当前阻塞项"。

| Blocker | Owner | 本文档解决 |
| --- | --- | --- |
| License 未确定 | project | §3.1（M10 前必须决策） |
| Maven groupId / 发布命名空间未最终确定 | project | §3.2（与 #11 §3.4 对齐） |

### 2.3 roadmap 第 2 节原则 10

> 可验证发布：每个模块必须有测试、文档、示例和发布检查项，不能只靠设计文档。

本组件是该原则的工程化落地：CI 验证测试、模块 README 提供文档、examples 提供示例、publishing checklist 提供发布检查项。

### 2.4 代码与文档现状

**现有模块**（`pom.xml` `<modules>` 列出 15 个）：

```text
helm-core / helm-agent-engine / helm-runtime
helm-sandbox-local / helm-provider-openai / helm-provider-anthropic
helm-http-core / helm-http-servlet / helm-cli / helm-spring-boot-starter
helm-persistence-jdbc / helm-observability-logging
examples/coding-workflow / examples/memory-session-example / examples/spring-boot-example
```

**当前 groupId**：`io.agent`（与生产包命名空间 `io.agent.helm` 不一致，#11 §3.4 已决策迁移）。

**当前 version**：`0.1.0-SNAPSHOT`。

**JDK**：`<maven.compiler.release>21</maven.compiler.release>`。

**Spotless**：`palantirJavaFormat 2.50.0`（PALANTIR style，`formatJavadoc=true`），markdown 也纳入 Spotless 检查。

**散落的 ContractTest 基类**（位于 `helm-core/src/test/java`，已发布 test-jar）：

| 基类 | 路径 | 被哪些模块消费（test scope, test-jar） |
| --- | --- | --- |
| `ModelProviderContractTest` | `helm-core/src/test/java/io/agent/helm/core/model/` | `helm-runtime`（FakeProvider）、`helm-provider-openai`、`helm-provider-anthropic` |
| `SandboxContractTest` | `helm-core/src/test/java/io/agent/helm/core/sandbox/` | `helm-sandbox-local`（InMemory + Local） |
| `RuntimeStoreContractTest` | `helm-core/src/test/java/io/agent/helm/core/store/` | `helm-runtime`（InMemory）、`helm-persistence-jdbc`（JdbcRuntimeStore） |
| `MemoryStoreContractTest` | `helm-core/src/test/java/io/agent/helm/core/memory/` | `helm-runtime`（InMemory）、`helm-persistence-jdbc`（JdbcMemoryStore） |

**问题**：合约测试基类放在 `helm-core` 的 test 目录，本意是 core 自测用的合约。但 OpenAI/Anthropic/JDBC/Sandbox 都是独立 adapter 模块，它们依赖 `helm-core` 的 test-jar 来复用合约测试——这把"core 的测试"和"对外发布的合约测试"混在一起，且外部 adapter 想复用必须依赖整个 `helm-core` test-jar（包含 core 自身的非合约测试 helper）。需要独立 `helm-runtime-testkit` 模块（roadmap M2 已提到，#11 §8.2 列为未决项，本组件落地）。

**`AGENTS.md` 现状**：仅描述 contributor 在本仓库内工作所需的 build/test/style 信息，没有覆盖外部贡献者流程、license、CI、publishing。

---

## 3. 设计方案

### 3.1 License 决策

#### 3.1.1 决策：Apache License 2.0

| 维度 | Apache 2.0 | MIT | GPL v3 |
| --- | --- | --- | --- |
| 商业使用 | 允许 | 允许 | 限制（衍生作品须 GPL） |
| 与 Java 生态主流一致 | Spring、Jackson、Jetty、SLF4J、Flyway 均为 Apache 2.0 | 部分（部分库） | 极少 |
| 与 SPI 适配器兼容 | 允许 proprietary adapter 链接 | 允许 | adapter 须 GPL，破坏 SPI 开放性 |
| 专利授权条款 | 有 | 无 | 有 |
| 与 JDK 自身 license 兼容 | 是 | 是 | 是 |

**决策：Apache License 2.0。**

理由：
1. Helm 是 SPI 框架，期望企业可以内部使用、写 proprietary adapter（OpenAI/Anthropic SDK 都是 proprietary 或 Apache 2.0）。Apache 2.0 允许 proprietary adapter 链接，MIT 也可但缺专利保护。
2. Java 生态主流（Spring Boot、Jackson、Jetty、SLF4J、Flyway）均为 Apache 2.0，对齐降低法务审查成本。
3. Apache 2.0 的专利反诉条款对企业采用更友好。
4. 备选 MIT：更宽松但缺专利授权，企业采用时仍需法务单独审。Apache 2.0 是 MIT 的超集，对企业采用更安全。

#### 3.1.2 LICENSE 文件

仓库根目录新增 `LICENSE`，内容为 Apache License 2.0 全文（来自 https://www.apache.org/licenses/LICENSE-2.0.txt）。

#### 3.1.3 源文件 header 策略

每个 `.java` 文件顶部必须有 header（Spotless 强制检查）：

```java
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Helm contributors. See LICENSE in the project root for details.
```

理由：
- 用 SPDX 标识符（`SPDX-License-Identifier: Apache-2.0`）符合 REUSE 规范，工具可机器校验。
- 不用完整 boilerplate（75 行），减少噪音；Apache 2.0 允许短形式 header + 仓库根 LICENSE 全文。
- `Copyright (c) Helm contributors` 不写具体年份（避免每年 bump），用集体名词。

Spotless 配置追加 `licenseHeader` 步骤（根 `pom.xml` Spotless `<java>` 块内）：

```xml
<licenseHeader>
  <content>// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Helm contributors. See LICENSE in the project root for details.</content>
  <delimiter>package </delimiter>
</licenseHeader>
```

`<delimiter>package </delimiter>` 让 Spotless 只在 `package` 声明前插入 header，避免破坏 module-info。

#### 3.1.4 README license 段更新

`README.md` 末尾"License"段从 "License 尚未确定" 改为：

```markdown
## License

Helm is distributed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text.

Contributions are accepted under the same license (Developer Certificate of Origin implicitly via Apache 2.0).
```

#### 3.1.5 NOTICE 文件

仓库根新增 `NOTICE`：

```text
Helm
Copyright (c) Helm contributors

This product includes software developed by the Helm contributors (https://github.com/agent-helm/helm).
```

### 3.2 Maven groupId / BOM / 命名空间

#### 3.2.1 groupId 迁移

对齐 API governance #11 §3.4 决策：groupId 从 `io.agent` 迁移到 `io.agent.helm`。

迁移步骤：
1. 根 `pom.xml` `<groupId>io.agent</groupId>` → `<groupId>io.agent.helm</groupId>`。
2. 所有子模块 `<parent><groupId>` 同步。
3. 所有跨模块 `<dependency>` 中 `io.agent` → `io.agent.helm`（`helm-runtime/pom.xml`、`helm-agent-engine/pom.xml` 等的跨模块引用）。
4. `examples/*/pom.xml` 同步。
5. `mvn verify` 全绿。
6. 因尚未发布，无 Maven Central 坐标兼容问题。

#### 3.2.2 artifactId 命名规则

| 规则 | 示例 |
| --- | --- |
| 核心模块：`helm-<layer>` | `helm-core`、`helm-agent-engine`、`helm-runtime` |
| Adapter 模块：`helm-<capability>-<impl>` | `helm-provider-openai`、`helm-persistence-jdbc`、`helm-sandbox-local`、`helm-observability-logging` |
| 集成层：`helm-<framework>` 或 `helm-<framework>-<role>` | `helm-http-core`、`helm-http-servlet`、`helm-cli`、`helm-spring-boot-starter` |
| BOM：`helm-bom` | — |
| Testkit：`helm-runtime-testkit` | — |
| Examples：`<scenario>-example`（位于 `examples/` 下） | `coding-workflow`、`memory-session-example`、`spring-boot-example` |

已是现状，文档化为规则即可。

#### 3.2.3 helm-bom 模块

新增 `helm-bom` 模块（packaging=pom），统一管理所有发布模块版本。模板与 #11 §3.4.2 一致，本组件落地：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.agent.helm</groupId>
    <artifactId>helm</artifactId>
    <version>0.2.0-SNAPSHOT</version>
  </parent>
  <artifactId>helm-bom</artifactId>
  <packaging>pom</packaging>

  <name>Helm BOM</name>
  <description>Bill of Materials for Helm modules.</description>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-agent-engine</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-runtime</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-runtime-testkit</artifactId>
        <version>${project.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-persistence-jdbc</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-provider-openai</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-provider-anthropic</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-sandbox-local</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-http-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-http-servlet</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-cli</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-spring-boot-starter</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-observability-logging</artifactId>
        <version>${project.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

注意：`helm-runtime-testkit` 在 BOM 中标 `<scope>test</scope>`，因为它是测试期依赖，不进生产 classpath。

应用消费方式：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.agent.helm</groupId>
      <artifactId>helm-bom</artifactId>
      <version>0.2.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>io.agent.helm</groupId>
    <artifactId>helm-spring-boot-starter</artifactId>
  </dependency>
</dependencies>
```

### 3.3 Maven wrapper

#### 3.3.1 添加方式

Maven Wrapper 通过 `mvn wrapper:wrapper` 一次性生成：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -N wrapper:wrapper -Dmaven=3.9.8 -Dtype=source
```

生成物：

| 文件 | 用途 |
| --- | --- |
| `mvnw` | Unix/macOS 启动脚本 |
| `mvnw.cmd` | Windows 启动脚本 |
| `.mvn/wrapper/maven-wrapper.properties` | 指定下载的 Maven 版本与下载 URL |
| `.mvn/wrapper/maven-wrapper.jar` | wrapper 启动器（`-Dtype=source` 时为 source form，更小） |

#### 3.3.2 JDK 21 要求

`mvnw` 本身不强制 JDK 版本，JDK 检查通过 `pom.xml` 的 `<maven.compiler.release>21</maven.compiler.release>` 在编译期强制。但为给 contributor 更早失败信号，`.mvn/jvm.config` 不需要特殊配置，依赖 compiler plugin 报错。

在 `CONTRIBUTING.md`（§3.8）中明确写 JDK 21 是唯一支持的构建 JDK，并给出安装命令：

```bash
# macOS
brew install openjdk@21
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH=$JAVA_HOME/bin:$PATH

# 验证
./mvnw -v
```

#### 3.3.3 .mvn/jvm.config（可选）

不需要。Helm 没有需要 JVM 参数定制的 build step（spotless / surefire 默认配置足够）。

### 3.4 CI（GitHub Actions）

#### 3.4.1 workflow 文件设计

新增 `.github/workflows/ci.yml`：

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read

jobs:
  build-and-test:
    name: Build and Test (JDK ${{ matrix.java }})
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        java: ['21']
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK ${{ matrix.java }}
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: ${{ matrix.java }}
          cache: maven

      - name: Cache Maven repository
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-m2-

      - name: Compile
        run: ./mvnw -B -ntp compile

      - name: Spotless check
        run: ./mvnw -B -ntp spotless:check

      - name: Test (mvn verify)
        run: ./mvnw -B -ntp verify

      - name: Javadoc generation (aggregated)
        run: ./mvnw -B -ntp -DskipTests javadoc:aggregate

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results-jdk-${{ matrix.java }}
          path: |
            **/target/surefire-reports/*.xml
            **/target/site/apidocs/
          retention-days: 7

  dependency-scan:
    name: Dependency Vulnerability Scan
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: OWASP dependency-check
        run: ./mvnw -B -ntp org.owasp:dependency-check-maven:10.0.4:check \
          -DfailBuildOnCVSS=7 \
          -DsuppressionFile=.github/dependency-check-suppressions.xml

      - name: Upload dependency-check report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: dependency-check-report
          path: target/dependency-check-report.html
          retention-days: 14
```

#### 3.4.2 CI jobs 说明

| Job | 触发 | 失败条件 | 用途 |
| --- | --- | --- | --- |
| `build-and-test` | push / PR | compile 失败、spotless check 失败、verify 失败、javadoc 失败 | 主验证：编译 → 格式 → 测试 → javadoc |
| `dependency-scan` | push / PR | CVSS >= 7 的已知漏洞 | 依赖漏洞扫描 |

#### 3.4.3 matrix 设计

当前只 JDK 21（`<maven.compiler.release>21</maven.compiler.release>` 强制）。matrix 留扩展位：

```yaml
matrix:
  java: ['21']
  # 后续可加 '22' early-access，或 os: [ubuntu-latest, macos-latest, windows-latest]
```

不立即加多个 JDK，因为 JDK 21 是 LTS 且是 Helm 唯一支持版本；CI 矩阵过宽会增加维护成本。等到 1.0 前再考虑加 macOS / Windows 验证（避免路径分隔符 bug）。

#### 3.4.4 Spotless 在 CI 的角色

`spotless:check` 在 `verify` phase 已自动执行（根 pom `<executions>` 配置），但 CI 单独跑一次 `spotless:check` 是为了让格式错误更早 fail（在 compile 后、test 前），缩短反馈时间。

#### 3.4.5 Javadoc 生成

`./mvnw javadoc:aggregate` 生成聚合 Javadoc 到 `target/site/apidocs/`。失败条件：Javadoc 严格模式（`-Werror` 不开，但 `palantirJavaFormat` 的 `formatJavadoc=true` 已保证格式）。CI 校验 Javadoc 不报错（如未关闭的标签、坏 link）。

不发到 GitHub Pages（M10 不要求），只 artifact 保留 7 天，发布时再决定是否 deploy。

#### 3.4.6 dependency-check suppression

新增 `.github/dependency-check-suppressions.xml`，预先 suppress 已知误报（如 test scope 的 H2/wiremock 不进生产 classpath）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
  <suppress>
    <packageUrl regex="true">^pkg:maven/com\.h2database/h2@.*$</packageUrl>
    <cve>CVE-2022-23221</cve>
    <cve>CVE-2021-23463</cve>
    <notes>H2 is test-scope only; not shipped in production artifacts.</notes>
  </suppress>
  <suppress>
    <packageUrl regex="true">^pkg:maven/org\.wiremock/wiremock@.*$</packageUrl>
    <notes>WireMock is test-scope only; not shipped in production artifacts.</notes>
  </suppress>
</suppressions>
```

#### 3.4.7 dependabot（互补）

新增 `.github/dependabot.yml`，每周检查依赖更新：

```yaml
version: 2
updates:
  - package-ecosystem: maven
    directory: /
    schedule:
      interval: weekly
    open-pull-requests-limit: 5
    labels: [dependencies]
    groups:
      helm-modules:
        patterns:
          - 'io.agent.helm:*'
      test-libs:
        patterns:
          - 'org.junit.jupiter:*'
          - 'org.assertj:*'
          - 'org.wiremock:*'
          - 'com.h2database:*'
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: monthly
    labels: [github-actions]
```

dependabot 提 PR，CI 自动验证是否破坏构建；dependency-check 在 CI 内对当前已 lock 版本扫漏洞。两者互补：dependabot 看版本新鲜度，dependency-check 看已知 CVE。

### 3.5 Maven publishing checklist

#### 3.5.1 发布坐标与版本号策略

| 项 | 决策 |
| --- | --- |
| groupId | `io.agent.helm`（§3.2.1） |
| artifactId | 各模块名（§3.2.2） |
| version | `0.x.0-SNAPSHOT` → `0.x.0` 发布 |
| pre-1.0 语义 | minor 版本可破坏 Public/SPI（对齐 #11 §3.2.1），patch 仅修 bug |
| 发布频率 | 每个 minor 一次正式发布；patch 按需 |

版本号示例：

- `0.2.0-SNAPSHOT` → 发布 `0.2.0`（含 OpenAI/Anthropic/JDBC/Spring Boot 等 adapter 已落地后第一次对外发布）
- `0.2.1`（patch：bug 修复）
- `0.3.0`（minor：新增 streaming API / authorizer，破坏 pre-1.0 API 时按 #11 §3.2.2 流程 deprecate）

#### 3.5.2 Sonatype Central Portal 流程

Helm 发布到 Maven Central，走 Sonatype Central Portal（2024 起替代旧 OSSRH）。

**一次性配置**：
1. 注册 Sonatype Central Portal 账号，namespace `io.agent.helm`（需 DNS 验证：TXT record `helm-verification=<token>` 指向 `agent-helm` 的 GitHub 仓库或自有域名）。
2. 生成 GPG key（RSA 4096），上传公钥到 `keyserver.ubuntu.com`。
3. GitHub Actions secrets 配置：

| Secret | 内容 |
| --- | --- |
| `CENTRAL_PORTAL_USERNAME` | Sonatype Central Portal 用户名 |
| `CENTRAL_PORTAL_PASSWORD` | Sonatype Central Portal password |
| `GPG_PRIVATE_KEY` | GPG 私钥（ASCII armored） |
| `GPG_PASSPHRASE` | GPG 私钥 passphrase |

#### 3.5.3 发布所需插件与 sources/javadoc jar

根 `pom.xml` `<build><pluginManagement>` 追加：

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-source-plugin</artifactId>
  <version>3.3.1</version>
  <executions>
    <execution>
      <id>attach-sources</id>
      <goals><goal>jar-no-fork</goal></goals>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-javadoc-plugin</artifactId>
  <version>3.10.1</version>
  <configuration>
    <release>21</release>
    <doclint>none</doclint>
    <failOnWarning>false</failOnWarning>
  </configuration>
  <executions>
    <execution>
      <id>attach-javadocs</id>
      <goals><goal>jar</goal></goals>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-gpg-plugin</artifactId>
  <version>3.2.7</version>
  <configuration>
    <gpgArguments>
      <arg>--pinentry-mode</arg>
      <arg>loopback</arg>
    </gpgArguments>
  </configuration>
</plugin>
<plugin>
  <groupId>org.sonatype.central</groupId>
  <artifactId>central-publishing-maven-plugin</artifactId>
  <version>0.6.0</version>
  <configuration>
    <publishingServerId>central</publishingServerId>
    <deploymentName>Helm ${project.version}</deploymentName>
  </configuration>
</plugin>
```

#### 3.5.4 发布 profile

根 `pom.xml` 加 `release` profile：

```xml
<profiles>
  <profile>
    <id>release</id>
    <build>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-source-plugin</artifactId>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-javadoc-plugin</artifactId>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-gpg-plugin</artifactId>
          <executions>
            <execution>
              <id>sign-artifacts</id>
              <phase>verify</phase>
              <goals><goal>sign</goal></goals>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>org.sonatype.central</groupId>
          <artifactId>central-publishing-maven-plugin</artifactId>
          <executions>
            <execution>
              <id>default-deploy</id>
              <phase>deploy</phase>
              <goals><goal>publish</goal></goals>
            </execution>
          </executions>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

#### 3.5.5 发布 checklist 文档结构

新增 `docs/release/publishing-checklist.md`，结构：

```markdown
# Maven Central Publishing Checklist

## One-time setup
- [ ] Sonatype Central Portal account created
- [ ] Namespace `io.agent.helm` verified (DNS TXT record)
- [ ] GPG key generated (RSA 4096), passphrase saved in password manager
- [ ] GPG public key uploaded to keyserver.ubuntu.com
- [ ] GitHub Actions secrets configured: CENTRAL_PORTAL_USERNAME, CENTRAL_PORTAL_PASSWORD, GPG_PRIVATE_KEY, GPG_PASSPHRASE
- [ ] settings.xml `<server>` entry for `central` configured locally

## Per-release checklist
- [ ] Update CHANGELOG.md with release notes
- [ ] Update version: `./mvnw -B -ntp versions:set -DnewVersion=0.x.0 -DprocessAllModules`
- [ ] Commit: `chore(release): prepare 0.x.0`
- [ ] Tag: `git tag v0.x.0`
- [ ] Push tag: `git push origin v0.x.0`
- [ ] Trigger release workflow (GitHub Actions `release.yml`, on tag)
- [ ] CI runs `./mvnw -B -ntp -Prelease deploy`
- [ ] Verify staging bundle uploaded to Central Portal
- [ ] Approve publishing in Central Portal UI (auto-publish enabled after first release)
- [ ] Wait for Maven Central sync (~30 min)
- [ ] Verify: `curl https://repo1.maven.org/maven2/io/agent/helm/helm-core/0.x.0/helm-core-0.x.0.pom`
- [ ] Update root README to point at new version
- [ ] Bump dev version: `./mvnw -B -ntp versions:set -DnewVersion=0.(x+1).0-SNAPSHOT -DprocessAllModules`
- [ ] Commit: `chore(dev): bump to 0.(x+1).0-SNAPSHOT`

## Artifact requirements
- [ ] Sources jar attached (helm-*-sources.jar)
- [ ] Javadoc jar attached (helm-*-javadoc.jar)
- [ ] POM files contain <name>, <description>, <url>, <licenses>, <scm>, <developers>
- [ ] All artifacts GPG signed (helm-*.asc)

## Rollback
- [ ] If staging fails: drop staging repository in Central Portal
- [ ] If published artifact broken: cannot un-publish; publish 0.x.1 with fix
- [ ] Document incident in docs/release/incidents.md
```

#### 3.5.6 发布 POM 元信息

每个发布模块 pom 需补全元信息（根 pom 集中定义，子模块继承）：

```xml
<url>https://github.com/agent-helm/helm</url>
<licenses>
  <license>
    <name>Apache License, Version 2.0</name>
    <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
  </license>
</licenses>
<scm>
  <connection>scm:git:git://github.com/agent-helm/helm.git</connection>
  <developerConnection>scm:git:ssh://git@github.com/agent-helm/helm.git</developerConnection>
  <url>https://github.com/agent-helm/helm</url>
</scm>
<developers>
  <developer>
    <id>helm-contributors</id>
    <name>Helm Contributors</name>
    <url>https://github.com/agent-helm/helm</url>
  </developer>
</developers>
```

### 3.6 helm-runtime-testkit 模块

#### 3.6.1 目标

把 `helm-core/src/test/java` 下的 4 个 ContractTest 基类迁移到独立 `helm-runtime-testkit` 模块，发布 test-jar 供外部 adapter 复用。

#### 3.6.2 模块结构

```text
helm-runtime-testkit/
  pom.xml
  src/main/java/io/agent/helm/testkit/
    ModelProviderContractTest.java     ← 从 helm-core/src/test 迁移
    SandboxContractTest.java          ← 从 helm-core/src/test 迁移
    RuntimeStoreContractTest.java     ← 从 helm-core/src/test 迁移
    MemoryStoreContractTest.java      ← 从 helm-core/src/test 迁移
    AbstractContractTest.java         ← 公共 helper（assertj 工具方法）
```

注意：合约测试基类放在 `src/main/java`（不是 `src/test/java`），因为它们是要被打包发布、被其他模块消费的类型。`testkit` 模块自身有少量 `src/test/java` 用于自测（验证基类在 FakeProvider/InMemoryStore 上跑得通）。

#### 3.6.3 testkit pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.agent.helm</groupId>
    <artifactId>helm</artifactId>
    <version>0.2.0-SNAPSHOT</version>
  </parent>
  <artifactId>helm-runtime-testkit</artifactId>

  <name>Helm Runtime Testkit</name>
  <description>Contract test base classes for Helm SPI adapters.</description>

  <dependencies>
    <!-- 编译期依赖 helm-core 的 SPI 类型（ModelProvider, Sandbox, RuntimeStore, MemoryStore 等） -->
    <dependency>
      <groupId>io.agent.helm</groupId>
      <artifactId>helm-core</artifactId>
      <scope>compile</scope>
    </dependency>
    <!-- 合约测试用到的断言/测试框架是 testkit 的 API 的一部分 -->
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>compile</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>compile</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- 把 testkit 自身的 src/test 也打 test-jar，给 in-tree 模块自测用 -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <executions>
          <execution>
            <id>test-jar</id>
            <goals><goal>test-jar</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

注意：testkit 把 `assertj-core` / `junit-jupiter` 改成 `compile` scope（不是 `test`），因为这些是 testkit API 的一部分，消费者 adapter 的 test 代码会 import `ModelProviderContractTest` 并使用其 `assertj` 断言方法。

#### 3.6.4 迁移计划

| 步骤 | 动作 | 影响范围 |
| --- | --- | --- |
| 1 | 新增 `helm-runtime-testkit` 模块，迁入 4 个 ContractTest 基类到 `src/main/java/io/agent/helm/testkit/`。 | 新模块 |
| 2 | `helm-core/pom.xml` 删除 `maven-jar-plugin` test-jar execution（不再需要发布 core test-jar）。 | core 自身测试不再被外部依赖 |
| 3 | `helm-runtime/pom.xml` 把对 `helm-core` 的 `test-jar` 依赖改为对 `helm-runtime-testkit` 的 `test` scope 依赖。 | runtime 测试通过 testkit 复用合约 |
| 4 | `helm-provider-openai`、`helm-provider-anthropic`、`helm-sandbox-local`、`helm-persistence-jdbc` 同步改依赖。 | 4 个 adapter 模块 |
| 5 | 把 4 个 ContractTest 基类的 `package` 声明从 `io.agent.helm.core.{model,sandbox,store,memory}` 改为 `io.agent.helm.testkit`。 | 包名变更，in-tree adapter 测试代码同步 import |
| 6 | 各 adapter 的 ContractTest 子类（如 `OpenAiProviderContractTest`）只改 `import` 与 `extends`，逻辑不变。 | 测试代码 import 调整 |
| 7 | `mvn verify` 全绿验证。 | — |

#### 3.6.5 兼容性说明

- 因为尚未正式发布，没有外部消费者依赖 `helm-core` 的 test-jar，迁移无破坏。
- #11 §3.5 的子接口拆分（`SessionStore`/`OperationStore`/`WorkflowRunStore`/`EventStore`）会新增对应 ContractTest 基类，统一放在 `helm-runtime-testkit`，本组件已为它预留位置。
- 未来外部 adapter 写 `MyCustomStore implements RuntimeStore` 后，只需 `extends RuntimeStoreContractTest` 即可获得完整合约测试覆盖。

### 3.7 CHANGELOG.md

新增仓库根 `CHANGELOG.md`，采用 [Keep a Changelog](https://keepachangelog.com/) 格式：

```markdown
# Changelog

All notable changes to Helm are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/) with the
pre-1.0 convention described in [docs/design/11-api-governance.md](docs/design/11-api-governance.md).

## [Unreleased]

### Added
- (在此追加未发布版本的新增项)

### Changed
- ...

### Deprecated
- ...

### Removed
- ...

### Fixed
- ...

### Security
- ...

## [0.1.0] - YYYY-MM-DD

### Added
- Initial MVP foundation: `helm-core`, `helm-agent-engine`, `helm-runtime`
  with FakeProvider, InMemoryRuntimeStore, basic event redaction.
- Provider adapters: `helm-provider-openai`, `helm-provider-anthropic`.
- Sandbox: `helm-sandbox-local` (InMemory + Local).
- HTTP integration: `helm-http-core`, `helm-http-servlet`, `helm-cli`.
- Spring Boot: `helm-spring-boot-starter`.
- Persistence: `helm-persistence-jdbc` (JdbcRuntimeStore + JdbcMemoryStore, Flyway migrations).
- Observability: `helm-observability-logging`.
- Memory & session management: `MemoryStore` SPI, `save_memory` tool,
  `maxSessionMessages` history trimming.
- Examples: `coding-workflow`, `memory-session-example`, `spring-boot-example`.

[Unreleased]: https://github.com/agent-helm/helm/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/agent-helm/helm/releases/tag/v0.1.0
```

规则：
- 每个 PR 必须在 `[Unreleased]` 下追加对应条目（`Added`/`Changed`/`Fixed`/`Security`）。
- 发布时把 `[Unreleased]` 改为 `[0.x.0] - <date>`，新建空 `[Unreleased]`。
- 条目格式：`<模块名>: <一句话描述>`，必要时附 PR 链接。

### 3.8 CONTRIBUTING.md

新增仓库根 `CONTRIBUTING.md`，结构：

```markdown
# Contributing to Helm

Thanks for your interest in contributing to Helm! This document covers the
development workflow, coding standards, and testing expectations.

## Getting started

### Prerequisites
- JDK 21 (LTS). Install via `brew install openjdk@21` on macOS.
- Maven (or use the bundled `./mvnw` wrapper, no separate install needed).
- Git.

### Build and verify
```bash
./mvnw -B -ntp verify
```
This runs compilation, unit tests, and Spotless format checks.

### Format
```bash
./mvnw -B -ntp spotless:apply
./mvnw -B -ntp spotless:check
```
Spotless is the source of truth for formatting. Never hand-format code
against Spotless output.

## Development workflow

1. **Fork** the repository and clone your fork.
2. **Branch**: create a feature branch from `main`:
   ```bash
   git checkout -b feat/my-feature
   ```
3. **Develop**: write tests first (TDD recommended), then implementation.
4. **Format**: run `./mvnw spotless:apply` before committing.
5. **Verify**: run `./mvnw verify` locally. If it fails, fix before pushing.
6. **Commit**: use conventional commits (see below).
7. **Push** and open a pull request against `main`.
8. **CI**: GitHub Actions runs compile, spotless, tests, dependency scan.
9. **Review**: address reviewer feedback. CRITICAL/HIGH issues block merge.
10. **Merge**: squash-and-merge after approval.

## Commit message format

```
<type>: <description>

<optional body>
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`.

Examples:
- `feat(provider): add streaming support to OpenAI adapter`
- `fix(jdbc): handle null session state on first load`
- `docs: update CHANGELOG for 0.2.0 release`

## Coding standards

- Keep `helm-core` independent of Spring, Servlet, CLI, provider SDKs, JDBC,
  and logging adapters (Core-first principle).
- Prefer small interfaces (1-3 methods) and immutable records/value objects.
- Wrap framework failures in structured `HelmException` subclasses.
- New SPI must come with a `ContractTest` base class in `helm-runtime-testkit`.
- New `HelmException` codes must be registered in `ErrorCode` enum (see
  [docs/design/11-api-governance.md](docs/design/11-api-governance.md)).

## Testing expectations

- Run `./mvnw verify` before considering a change complete.
- Use `FakeProvider`, `InMemoryRuntimeStore`, `InMemoryMemoryStore`, or
  `InMemorySandbox` for deterministic tests. Never introduce real provider
  credentials or network-dependent tests in CI.
- New SPI adapters must pass the corresponding contract test:
  - Provider: `extends ModelProviderContractTest`
  - Sandbox: `extends SandboxContractTest`
  - RuntimeStore: `extends RuntimeStoreContractTest`
  - MemoryStore: `extends MemoryStoreContractTest`
- If verification fails due to missing JDK 21, report it explicitly; do not
  claim the change is verified.

## License

By contributing, you agree your contributions are licensed under the Apache
License, Version 2.0 (see [LICENSE](LICENSE)).
```

### 3.9 package-specific READMEs

每个模块根目录新增 `README.md`，统一模板：

```markdown
# helm-<module-name>

<一句话用途>

## What it does

<2-3 句话说明该模块在 Helm 中的角色>

## Dependencies

<列出 Maven 坐标与依赖关系>

```xml
<dependency>
  <groupId>io.agent.helm</groupId>
  <artifactId>helm-<module-name></artifactId>
</dependency>
```

## API quick reference

<列出该模块对外的关键类型/方法，每个一行说明>

## Example

<最小可运行代码片段，3-10 行>

## Tests

```bash
./mvnw -B -ntp -pl helm-<module-name> test
```

<列出该模块的测试覆盖范围>
```

各模块 README 内容示例（不在此文档展开全文，仅列模板与位置）：

| 模块 | README 位置 | 关键内容 |
| --- | --- | --- |
| helm-core | `helm-core/README.md` | SPI 总览：ModelProvider/Sandbox/RuntimeStore/MemoryStore/Tool/AgentDefinition/WorkflowDefinition；不可依赖项 |
| helm-agent-engine | `helm-agent-engine/README.md` | AgentLoop/TurnRunner/ToolCallOrchestrator/ContextManager；engine 事件 |
| helm-runtime | `helm-runtime/README.md` | AgentRuntime/WorkflowRuntime 入口、session/memory 管理 |
| helm-runtime-testkit | `helm-runtime-testkit/README.md` | 4 个 ContractTest 基类用途与使用方法 |
| helm-provider-openai | `helm-provider-openai/README.md` | OpenAI 适配、credential 来源、streaming |
| helm-provider-anthropic | `helm-provider-anthropic/README.md` | Anthropic 适配、credential 来源 |
| helm-persistence-jdbc | `helm-persistence-jdbc/README.md` | JdbcRuntimeStore、Flyway migrations、H2/PostgreSQL 支持 |
| helm-sandbox-local | `helm-sandbox-local/README.md` | InMemorySandbox/LocalSandbox、安全边界 |
| helm-http-core | `helm-http-core/README.md` | framework-neutral DTO/handler |
| helm-http-servlet | `helm-http-servlet/README.md` | Jakarta Servlet adapter |
| helm-cli | `helm-cli/README.md` | helm run/dev/inspect 命令 |
| helm-spring-boot-starter | `helm-spring-boot-starter/README.md` | auto-config、bean discovery、properties |
| helm-observability-logging | `helm-observability-logging/README.md` | LoggingRuntimeObserver、redaction 策略 |
| helm-bom | `helm-bom/README.md` | BOM 引入方式 |

### 3.10 adapter implementation guides

新增 `docs/guides/` 目录，每篇一篇：

#### 3.10.1 Provider adapter guide

`docs/guides/adapter-provider.md` 大纲：

1. 何时写新 provider adapter（`ModelProvider` SPI 用途）。
2. 实现 `ModelProvider` 接口：`name()`、`stream(ModelRequest, Flow.Publisher<ModelStreamEvent>)`。
3. 必须 extend `ModelProviderContractTest`：mock server 契约测试模板。
4. credential 来源：环境变量 / 配置文件 / secret manager，绝不硬编码。
5. provider error taxonomy：`ProviderException` 多 code 模式（PROVIDER_ERROR/PROVIDER_RATE_LIMITED/PROVIDER_TIMEOUT）。
6. JsonSchema 到 provider tool schema 映射规则。
7. credential redaction：API key 不进 events/logs/safe errors（对齐 #11 §3.3.3）。
8. 不依赖 runtime internals（只依赖 `helm-core`）。
9. 发布坐标与 BOM 注册。

#### 3.10.2 Persistence adapter guide

`docs/guides/adapter-persistence.md` 大纲：

1. `RuntimeStore` 聚合 facade + 子接口（对齐 #11 §3.5）。
2. 实现 `RuntimeStore` 全接口或仅实现子接口（`SessionStore`/`OperationStore`/...）。
3. 必须 extend `RuntimeStoreContractTest`（聚合）或对应子接口 ContractTest。
4. session version / optimistic locking 合约。
5. event ordering / append-only 合约。
6. schema migration 策略（Flyway 例子：`V1__init.sql`、`V2__memory.sql`）。
7. SQL 异常映射 `PersistenceException`（code=PERSISTENCE_ERROR）。
8. 在 `helm-runtime-testkit` 注册新测试模块（若需新增 ContractTest 基类）。

#### 3.10.3 Sandbox adapter guide

`docs/guides/adapter-sandbox.md` 大纲：

1. `Sandbox` SPI：`SandboxFileSystem` + `SandboxShell`。
2. 必须 extend `SandboxContractTest`。
3. 路径 normalize、拒绝 traversal/absolute escape/symlink escape 合约。
4. shell 默认关闭，allowed env 显式配置。
5. LocalSandbox 不是生产隔离（文档明确）；生产隔离依赖 container/VM/remote adapter（组件 #9）。
6. timeout、output limit、command policy。
7. `SANDBOX_ERROR` code 使用约束。

#### 3.10.4 Observability adapter guide

`docs/guides/adapter-observability.md` 大纲：

1. `RuntimeEventObserver` SPI（标 `@Experimental`，#11 §3.2.3）。
2. 实现 `onEvent(RuntimeEventRecord)`：默认 redact developerDetails。
3. content capture policy：默认只记 metadata/summary。
4. logging observer 现有实现参考（`helm-observability-logging`）。
5. metrics / OpenTelemetry 适配器在组件 #8 落地后的接入方式。
6. observer 不应阻塞 runtime 主线程（同步快速返回或异步 queue）。

#### 3.10.5 Spring Boot starter guide

`docs/guides/adapter-spring-boot.md` 大纲：

1. `helm-spring-boot-starter` 是集成层，不属 core。
2. auto-config `AgentRuntime` / `WorkflowRuntime`：bean discovery 规则。
3. properties：`helm.agents.*`、`helm.workflows.*`、`helm.http.enabled`。
4. conditional HTTP route registration（默认关闭，对齐安全默认）。
5. duplicate agent/workflow/tool names fail-fast。
6. Spring Boot example 现有参考（`examples/spring-boot-example`）。
7. core/runtime/engine 保持 Spring-free 的检查方法。

### 3.11 Examples 矩阵与 clean consumer sample

#### 3.11.1 现有 examples

| Example | 位置 | 覆盖能力 |
| --- | --- | --- |
| coding-workflow | `examples/coding-workflow/` | FakeProvider + workflow（软件开发自动化） |
| memory-session-example | `examples/memory-session-example/` | Memory + session 管理 + tool 调用 + operation 检查 |
| spring-boot-example | `examples/spring-boot-example/` | Spring Boot starter auto-config + HTTP |

#### 3.11.2 补缺 examples 清单

| 新 example | 位置 | 目标 | 依赖组件 |
| --- | --- | --- | --- |
| FakeProvider standalone | `examples/fake-provider-standalone/` | 最小可运行：单文件 main 启动 AgentRuntime + FakeProvider 跑一个 prompt | 现有 |
| Real provider (OpenAI) | `examples/openai-provider-example/` | 真实 LLM 调用，需 `OPENAI_API_KEY`；CI 默认不跑 | 现有 |
| Real provider (Anthropic) | `examples/anthropic-provider-example/` | Anthropic 真实调用，需 `ANTHROPIC_API_KEY`；CI 默认不跑 | 现有 |
| JDBC persistence | `examples/jdbc-persistence-example/` | H2 文件模式 + JdbcRuntimeStore，session 重启恢复 | 现有 |
| HTTP server | `examples/http-server-example/` | 启动 HelmHttpServlet + Jetty，curl 调用 prompt/dispatch | 现有 |
| Sandbox | `examples/sandbox-example/` | LocalSandbox 文件读写 + shell 默认关闭演示 | 现有 |
| Typed tools | `examples/typed-tools-example/` | JsonSchema record + enum + optional，tool input/output validation | 现有（基础）/ #3（扩展） |
| Streaming | `examples/streaming-example/` | token-level streaming 暴露 | #1 streaming API |
| Durable workflow | `examples/durable-workflow-example/` | async dispatch + lease + recovery 演示 | #9 durable scale |
| Memory semantic | `examples/memory-semantic-example/` | 向量检索 MemoryStore 演示 | #4 memory 语义检索 |
| Authorizer | `examples/authorizer-example/` | HelmAuthorizer + HTTP route 授权演示 | #5 authorizer |
| Spring Boot + JDBC | `examples/spring-boot-jdbc-example/` | Spring Boot starter + JDBC store + memory + 多 agent | 现有 |

标注"现有"的可在本组件落地；标了组件编号的等到对应组件落地后补。

每个 example 必须满足：
- 有 `README.md` 说明用途、依赖、运行方式。
- `mvn -pl examples/<name> verify` 可独立构建（依赖 helm 模块先 install）。
- 默认不依赖真实 credential / 网络（real provider example 需 `OPENAI_API_KEY` 环境变量）。

#### 3.11.3 clean consumer sample

**目标**：验证 Helm 已发布 Maven artifacts 可被外部 Java 项目独立消费。

**形态选择**：独立外部仓库 `agent-helm/helm-consumer-sample`（GitHub），不放在 Helm 主仓库。理由：
- 主仓库 `examples/` 用 `${project.version}` SNAPSHOT 依赖，验证不到"外部从 Maven Central 拉取"路径。
- 独立仓库从 `https://repo1.maven.org/maven2/` 拉已发布版本，能真实模拟外部采用者。

**consumer sample 内容**：

```text
helm-consumer-sample/
  pom.xml                ← 引入 helm-bom + helm-spring-boot-starter（无 SNAPSHOT）
  README.md              ← 如何运行
  src/main/java/io/agent/helm/sample/
    MinimalConsumer.java ← 最简：AgentRuntime + FakeProvider 跑一个 prompt
    SpringBootConsumer.java ← Spring Boot 应用，含一个 agent + 一个 tool
```

`pom.xml`：

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.agent.helm.sample</groupId>
  <artifactId>helm-consumer-sample</artifactId>
  <version>1.0.0</version>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <helm.version>0.2.0</helm.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.agent.helm</groupId>
        <artifactId>helm-bom</artifactId>
        <version>${helm.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>io.agent.helm</groupId>
      <artifactId>helm-runtime</artifactId>
    </dependency>
    <dependency>
      <groupId>io.agent.helm</groupId>
      <artifactId>helm-spring-boot-starter</artifactId>
    </dependency>
  </dependencies>
</project>
```

**集成测试验证**：consumer sample 的 CI 每晚 / Helm 发布后触发，从 Maven Central 拉版本并跑 `mvn verify`。失败说明 Helm 发布有问题（坐标错、缺 jar、API 破坏未 deprecate）。

**M10 阶段性策略**：未正式发布到 Maven Central 前，consumer sample 验证"从本地 `mvn install` 后消费"路径：Helm 主仓库 `mvn install` 到本地 `~/.m2`，consumer sample 在同一机器上 `mvn verify`。等首次发布后再切到从 Maven Central 拉取。

---

## 4. 数据流与时序

### 4.1 发布流程时序

```text
┌────────────┐   PR 合并    ┌──────────┐  触发    ┌──────────────┐
│ Developer  │ ──────────> │ main 分支 │ ───────> │ CI (ci.yml)   │
│ (本地开发) │              │          │          │ build-and-test│
└────────────┘              └──────────┘          └──────┬───────┘
      │                                                  │
      │ ./mvnw verify                                    │ compile + spotless
      │ 全绿才提交 PR                                    │ + verify + javadoc
      ▼                                                  ▼
┌────────────┐                                  ┌──────────────────┐
│ CI 通过    │ <────────────────────────────── │ CI 报告           │
│ merge      │                                  │ dependency-scan   │
└──────┬─────┘                                  └──────────────────┘
       │
       │ 准备发布：版本号 / CHANGELOG / tag
       ▼
┌──────────────────┐   git tag v0.x.0   ┌────────────────────────┐
│ Release engineer │ ─────────────────> │ release workflow        │
│ (版本管理员)     │   git push --tags  │ (.github/workflows/     │
└──────────────────┘                    │  release.yml, on tag)   │
                                        └────────────┬────────────┘
                                                     │
                                                     │ ./mvnw -Prelease deploy
                                                     │ (sources + javadoc + GPG
                                                     │  + central-publishing-plugin)
                                                     ▼
                                        ┌────────────────────────┐
                                        │ Sonatype Central Portal│
                                        │ (staging)              │
                                        └────────────┬────────────┘
                                                     │
                                                     │ manual approve
                                                     │ (首次发布后可自动)
                                                     ▼
                                        ┌────────────────────────┐
                                        │ Maven Central          │
                                        │ (~30 min sync)         │
                                        └────────────┬────────────┘
                                                     │
                                                     │ consumer sample CI 检测到新版本
                                                     ▼
                                        ┌────────────────────────┐
                                        │ helm-consumer-sample    │
                                        │ (独立仓库 CI)           │
                                        │ mvn verify (拉中央仓库) │
                                        └────────────┬────────────┘
                                                     │
                                                     │ 通过 → 发布成功
                                                     │ 失败 → 回滚（无法 un-publish，
                                                     │         发 0.x.1 修复）
                                                     ▼
                                        ┌────────────────────────┐
                                        │ 更新 root README 版本  │
                                        │ bump dev 版本到 SNAPSHOT│
                                        │ 发布 CHANGELOG 段       │
                                        └────────────────────────┘
```

### 4.2 API 变更 → docs 同步检查

PR 触及 public/SPI 时，CI 不直接校验 docs 一致性（工具支持有限），但用检查清单约束：

```text
PR 改了 SPI/core API
  → reviewer 检查 docs/contracts/*.md 是否同步
  → reviewer 检查对应模块 README 的 "API quick reference" 是否同步
  → reviewer 检查 CHANGELOG.md [Unreleased] 是否追加条目
  → 若改了 SPI 形态：检查 helm-runtime-testkit 对应 ContractTest 基类是否更新
  → 若破坏性变更：检查 docs/contracts/api-baseline.md 是否更新（#11 §3.6）
  → 若改了 example API：检查 examples/*/README.md 是否同步
```

可机器化的部分（后续增量补）：
- Javadoc 生成不报错（CI 已校验）。
- japicmp 报告贴 PR（#11 §3.6）。
- `grep` 扫描 README 中提到的类名是否在源码中存在（轻量检查脚本，可选）。

---

## 5. 安全与边界

### 5.1 credential 不进 CI 日志

| 项 | 策略 |
| --- | --- |
| 真实 provider API key | CI 永不配置；real provider example 默认不跑；需手动 dispatch `workflow_dispatch` 且仅在 fork 不触发 |
| Sonatype Central Portal password | GitHub Actions secret `CENTRAL_PORTAL_PASSWORD`，`echo $SECRET | wc -c` 类调试命令 PR review 时拒绝 |
| GPG 私钥 | GitHub Actions secret `GPG_PRIVATE_KEY`，`release.yml` 内 `echo "$GPG_PRIVATE_KEY" | gpg --import`，不输出到 log |
| GitHub token | `GITHUB_TOKEN` 自动注入，权限最小化（`contents: write` 仅 release workflow） |

real provider example 的 CI 策略：

```yaml
# .github/workflows/real-provider-smoke.yml（手动触发）
name: Real Provider Smoke
on:
  workflow_dispatch:
    inputs:
      provider:
        description: 'openai or anthropic'
        required: true
        default: 'openai'
jobs:
  smoke:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Smoke test
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
          ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
        run: |
          ./mvnw -B -ntp -pl examples/${{ github.event.inputs.provider }}-provider-example verify
        # 日志中绝不 echo $OPENAI_API_KEY；surefire 默认不打印 env
```

### 5.2 GPG key 管理

- GPG 私钥 RSA 4096，passphrase 单独保存（密码管理器）。
- 私钥 ASCII armored 存 GitHub Actions secret `GPG_PRIVATE_KEY`；passphrase 存 `GPG_PASSPHRASE`。
- 公钥上传到 `keyserver.ubuntu.com`、`keys.openpgp.org`（多 keyserver 提升可用性）。
- 私钥轮换策略：每 2 年一次；旧 key 留 1 年过渡期再撤销。
- 私钥泄露应急：立即撤销 + 重新生成 + 通知 Maven Central 已签名 artifacts 可疑（已发布不可改，只能后续版本修正）。

### 5.3 dependency 漏洞扫描

- **CI 内**：OWASP dependency-check，CVSS >= 7 即 fail build（`.github/workflows/ci.yml` 的 `dependency-scan` job）。
- **CI 外**：dependabot 周 PR 提依赖更新，CI 自动验证。
- **scope 限定**：test scope 依赖（H2、wiremock）通过 suppression 文件标注，避免误报阻塞 CI。
- **生产 classpath 审计**：发布前 `./mvnw dependency:list -Prelease`，确认 `runtime`/`compile` scope 无已知 CVE。
- **新依赖引入流程**：PR 引入新 `<dependency>` 时，reviewer 必须检查 license 兼容性（Apache 2.0 / MIT / BSD / EPL 兼容；GPL 不兼容）。

### 5.4 license header 强制

- Spotless `licenseHeader` 步骤在每个 `.java` 文件顶部强制 SPDX header（§3.1.3）。
- `spotless:check` 失败 → CI 失败 → PR 不合。
- `module-info.java` 通过 `<delimiter>package </delimiter>` 排除（module-info 没有 `package` 声明，Spotless 不插入 header）。
- 非 `.java` 文件（pom.xml、yml、md）不强制 header；markdown 由 Spotless markdown step 仅检查尾随空白与换行。

### 5.5 依赖守则

本组件不引入任何 core 禁止依赖：

- `helm-bom` 是 pom，无生产代码。
- `helm-runtime-testkit` 依赖 `helm-core`（SPI）+ assertj/junit（compile scope，因为是 testkit API）。
- Maven wrapper / Spotless / dependency-check / GPG plugin / central-publishing-plugin 都是 build tool，不进 runtime classpath。
- CI workflow 是 yaml，不进任何 jar。
- license header 不引入依赖。

---

## 6. 测试策略

### 6.1 CI 验证项

| CI 检查 | 命令 | 失败条件 |
| --- | --- | --- |
| 编译 | `./mvnw -B -ntp compile` | 任何编译错误 |
| 格式 | `./mvnw -B -ntp spotless:check` | Spotless 报告未格式化文件 |
| 测试 | `./mvnw -B -ntp verify` | 任何 surefire 失败；japicmp 破坏（#11） |
| Javadoc | `./mvnw -B -ntp javadoc:aggregate` | Javadoc 报错（缺 link、坏 tag） |
| 依赖扫描 | `dependency-check:check` | CVSS >= 7 |

### 6.2 testkit 合约测试覆盖

`helm-runtime-testkit` 的 4 个 ContractTest 基类在 in-tree adapter 上的覆盖矩阵：

| ContractTest 基类 | 覆盖 SPI | In-tree 实现测试 | 测试文件位置 |
| --- | --- | --- | --- |
| `ModelProviderContractTest` | `ModelProvider` | FakeProvider、OpenAI（mock）、Anthropic（mock） | `helm-runtime/src/test/.../FakeProviderContractTest`、`helm-provider-openai/src/test/.../OpenAiProviderContractTest`、`helm-provider-anthropic/src/test/.../AnthropicProviderContractTest` |
| `SandboxContractTest` | `Sandbox` | InMemorySandbox、LocalSandbox | `helm-sandbox-local/src/test/.../InMemorySandboxContractTest`、`helm-sandbox-local/src/test/.../LocalSandboxContractTest` |
| `RuntimeStoreContractTest` | `RuntimeStore` | InMemoryRuntimeStore、JdbcRuntimeStore | `helm-runtime/src/test/.../InMemoryRuntimeStoreContractTest`、`helm-persistence-jdbc/src/test/.../JdbcRuntimeStoreContractTest` |
| `MemoryStoreContractTest` | `MemoryStore` | InMemoryMemoryStore、JdbcMemoryStore | `helm-runtime/src/test/.../InMemoryMemoryStoreContractTest`、`helm-persistence-jdbc/src/test/.../JdbcMemoryStoreContractTest` |

迁移到 testkit 后，所有 adapter 测试改为 `import io.agent.helm.testkit.{ModelProviderContractTest, SandboxContractTest, RuntimeStoreContractTest, MemoryStoreContractTest}`，逻辑不变。

### 6.3 testkit 自身测试

`helm-runtime-testkit/src/test/java` 用 InMemory/Fake 实现验证 4 个 ContractTest 基类本身可执行（防止基类 refactor 后没人发现 in-memory 也跑不过）：

```java
package io.agent.helm.testkit;

import io.agent.helm.core.model.ModelProvider;
import io.agent.helm.runtime.FakeProvider;
import org.junit.jupiter.api.Test;

class TestkitSelfTest {
    @Test
    void fakeProviderPassesContractTest() {
        new FakeProviderContractTest().run();  // 简化示意
    }
}
```

### 6.4 consumer sample 集成测试

`helm-consumer-sample` 仓库 CI 验证：

| 检查 | 命令 | 失败条件 |
| --- | --- | --- |
| 从 Maven Central 拉取成功 | `mvn dependency:resolve` | 坐标不存在 / 网络问题 |
| 编译通过 | `mvn compile` | API 破坏未 deprecate（#11 §3.2 失效） |
| 运行通过 | `mvn exec:java -Dexec.mainClass=...MinimalConsumer` | 运行期行为破坏 |
| Spring Boot 应用启动 | `mvn spring-boot:run`（带 timeout） | auto-config 失败 / bean 冲突 |

consumer sample 失败应直接 block Helm 下次发布（视为 release regression）。

### 6.5 clean checkout build 测试

CI 的 `build-and-test` job 本身就是 clean checkout：

- `actions/checkout@v4` 全新 clone。
- `actions/cache@v4` 仅缓存 `~/.m2/repository`（依赖 jar），不缓存 `target/`。
- `./mvnw -B -ntp verify` 从空 `target/` 开始构建。

这保证"clean checkout build 通过"验收项由 CI 默认覆盖。

---

## 7. 验收标准

对齐 `docs/roadmap.md` 第 5 节 M10 验收。

### 7.1 M10 验收（直接对齐）

| roadmap M10 验收项 | 本组件达成方式 |
| --- | --- |
| clean checkout build 通过 | §6.5 CI `build-and-test` job 默认 clean checkout |
| 外部 sample project 可消费本地/发布 Maven artifacts | §3.11.3 consumer sample |
| docs 与实现 API 一致 | §4.2 reviewer 检查清单 + Javadoc 不报错 |
| examples 可执行，不只是目标 API 文档 | §3.11.2 每个 example 有 README + 可 `mvn verify` |

### 7.2 M10 交付清单（本组件补齐）

| M10 交付项 | 状态 |
| --- | --- |
| `CHANGELOG.md` | §3.7 |
| `CONTRIBUTING.md` | §3.8 |
| license 决策 | §3.1 Apache 2.0 |
| Maven wrapper | §3.3 |
| CI：compile、tests、Spotless、dependency checks、Javadocs | §3.4 |
| Maven publishing checklist | §3.5 |
| clean consumer sample | §3.11.3 |
| package-specific READMEs | §3.9 |
| adapter implementation guides | §3.10 |
| runnable examples | §3.11.2 |

### 7.3 本组件专属验收

- [ ] 根目录新增 `LICENSE`（Apache 2.0 全文）与 `NOTICE`。
- [ ] 每个 `.java` 文件顶部有 SPDX header，Spotless `licenseHeader` 强制。
- [ ] `README.md` "License" 段更新为 Apache 2.0。
- [ ] 根 `pom.xml` `<groupId>` 改为 `io.agent.helm`，所有子模块 pom 同步。
- [ ] 新增 `helm-bom` 模块，列入根 pom `<modules>`，BOM 列出全部发布模块。
- [ ] 新增 `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`。
- [ ] 新增 `.github/workflows/ci.yml`，含 `build-and-test` 与 `dependency-scan` 两个 job。
- [ ] 新增 `.github/dependabot.yml` 与 `.github/dependency-check-suppressions.xml`。
- [ ] 根 `pom.xml` 加 `release` profile（sources / javadoc / GPG / central-publishing-plugin）。
- [ ] 根 `pom.xml` 补全 `<url>` / `<licenses>` / `<scm>` / `<developers>` 元信息。
- [ ] 新增 `docs/release/publishing-checklist.md`。
- [ ] 新增 `helm-runtime-testkit` 模块，迁入 4 个 ContractTest 基类；`helm-core` 删除 test-jar execution；所有 in-tree adapter 改依赖 testkit；`mvn verify` 全绿。
- [ ] 新增 `CHANGELOG.md`（Keep a Changelog 格式，含 `[Unreleased]` 与 `[0.1.0]` 段）。
- [ ] 新增 `CONTRIBUTING.md`。
- [ ] 每个 helm-* 模块根目录有 `README.md`（§3.9 模板）。
- [ ] 新增 `docs/guides/adapter-provider.md`、`adapter-persistence.md`、`adapter-sandbox.md`、`adapter-observability.md`、`adapter-spring-boot.md`。
- [ ] 补齐 §3.11.2 表中标"现有"的 examples（FakeProvider standalone、real provider、JDBC、HTTP、sandbox、typed tools、Spring Boot + JDBC）。
- [ ] 创建独立 `helm-consumer-sample` 仓库，README 说明运行方式，CI 验证从 Maven Central 拉取。
- [ ] 阻塞项关闭：roadmap 第 7 节"License 未确定"与"Maven groupId 未最终确定"标记 resolved。

---

## 8. 风险与未决项

### 8.1 风险

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| groupId 迁移（`io.agent` → `io.agent.helm`）遗漏某处跨模块依赖，导致构建失败 | 中 | 同 PR 内 `grep -r 'io.agent' --include='pom.xml'` 全量扫描；`mvn verify` 全绿为门槛 |
| testkit 迁移破坏 in-tree adapter 测试 import | 中 | 同 PR 内同步所有 ContractTest 子类的 import；`mvn verify` 全绿 |
| Sonatype Central Portal namespace 验证需 DNS TXT record，但 Helm 暂无自有域名 | 中 | 用 GitHub Pages 域名验证方式（Sonatype 支持 GitHub 仓库验证）；或先发本地 `mvn install` 验证，发布延后到 namespace 批准 |
| dependency-check 误报阻塞 CI | 中 | suppression 文件预 suppress 已知 test-scope 误报；定期 review suppression 是否过时 |
| consumer sample 仓库与 Helm 主仓库版本不同步 | 低 | consumer sample CI 由 Helm release workflow 触发（tag push 时 webhook）；consumer sample README 列出兼容 Helm 版本表 |
| Spotless licenseHeader 与 palantirJavaFormat 顺序冲突 | 低 | Spotless 文档明确 licenseHeader 必须在 palantirJavaFormat 之前；按顺序配置即可 |
| examples 数量多导致 CI 时间变长 | 低 | examples 默认编译但不跑 verify（除非 `-pl examples/<name> verify`）；CI 只验证主仓库模块 |

### 8.2 未决项

| 未决项 | 说明 | 决策时点 |
| --- | --- | --- |
| `helm-consumer-sample` 仓库归属 | 独立 GitHub 仓库 vs 在主仓库 `examples/external-consumer/` 子目录。独立仓库验证更真实但需额外维护。 | 本组件落地时 |
| Maven Central 命名空间验证方式 | DNS TXT（需自有域名）vs GitHub 仓库验证（Sonatype 支持）。 | 首次发布前 |
| Javadoc 是否 deploy 到 GitHub Pages | M10 不要求，但 1.0 前可考虑。 | 1.0 前 |
| consumer sample 触发方式 | Helm release workflow webhook vs consumer sample 定时拉。 | 首次发布后 |
| 多 OS CI 矩阵 | 当前只 ubuntu-latest。macOS / Windows 验证推迟到 1.0 前。 | 1.0 前 |
| `helm-runtime-testkit` 是否纳入 `helm-bom` | 已纳入（§3.2.3 标 test scope）。但 BOM 通常不列 test-scope 依赖；是否单独维护 testkit BOM 待定。 | 本组件落地时 |
| examples 是否拆 sub-build | examples 数量增加后根 `mvn verify` 时间线性增长。是否引入 `<profiles>` 控制 examples 编译范围。 | examples 超过 8 个时 |

---

## 9. 与其他组件的关系

本组件是横向贯穿组件，与所有 SPI/adapter 组件都有依赖关系：testkit 是所有 SPI adapter 复用合约测试的基础，examples 依赖各组件落地。

| 组件 | 依赖本组件的什么 | 备注 |
| --- | --- | --- |
| #11 API governance | BOM 模块、groupId 迁移、japicmp CI 在本组件落地（#11 §3.6 已设计，本组件执行）；license header 由 #11 包归类规则与本组件 Spotless 配置共同约束。 | #11 是前置决策，本组件是工程化执行。 |
| #1 Streaming API | streaming example（§3.11.2）依赖 #1 落地；streaming API 标 `@Preview`，japicmp 豁免规则在本组件 CI 中执行。 | 等 #1 落地后补 example。 |
| #2 Engine hardening | engine 错误 code 在 `ErrorCode` 注册（#11 §3.3）；testkit 中 `ModelProviderContractTest` 可能扩展 engine 事件断言。 | |
| #3 JsonSchema 扩展 | typed-tools example 验证新 JsonSchema 类型；testkit 不直接覆盖 JsonSchema。 | |
| #4 Memory 语义检索 | memory-semantic example 依赖 #4；`MemoryStoreContractTest` 已存在，#4 替换 `search` 实现时不破坏合约。 | |
| #5 Authorizer | authorizer example 依赖 #5；新 `HelmAuthorizer` SPI 落地后 testkit 加 `AuthorizerContractTest` 基类。 | |
| #6 HTTP Client SDK | client SDK 模块加入 BOM；consumer sample 可能用 client SDK 而非直接 HTTP。 | |
| #7 Rate limiting | admission 错误 code 注册；rate limit 不直接依赖 testkit。 | |
| #8 Metrics / OpenTelemetry | observability adapter guide（§3.10.4）覆盖 OTel adapter 接入方式；`RuntimeEventObserver` 标 `@Experimental`，testkit 暂不强制合约测试。 | |
| #9 Durable scale runtime | durable-workflow example 依赖 #9；lease/journal API 标 `@Preview`。 | |

### 9.1 testkit 依赖关系

`helm-runtime-testkit` 是后续所有 SPI adapter 的隐式前置：

- #5 authorizer 落地后，新增 `AuthorizerContractTest` 基类到 testkit。
- #4 memory 语义检索替换 `search` 时，更新 `MemoryStoreContractTest` 加 `searchByTags` 等测试。
- #11 §3.5 子接口拆分后，新增 `SessionStoreContractTest` / `OperationStoreContractTest` / `WorkflowRunStoreContractTest` / `EventStoreContractTest` 到 testkit。
- 外部 adapter 写一个新 provider，只需 `extends ModelProviderContractTest` 即可获得完整合约覆盖，不必从头写测试。

### 9.2 examples 依赖关系

每个 example 依赖的组件：

| Example | 依赖组件 |
| --- | --- |
| fake-provider-standalone | 现有 core/engine/runtime |
| openai-provider-example | 现有 provider-openai |
| anthropic-provider-example | 现有 provider-anthropic |
| jdbc-persistence-example | 现有 persistence-jdbc |
| http-server-example | 现有 http-core/http-servlet |
| sandbox-example | 现有 sandbox-local |
| typed-tools-example | 现有 core（+ #3 扩展后增强） |
| streaming-example | #1 |
| durable-workflow-example | #9 |
| memory-semantic-example | #4 |
| authorizer-example | #5 |
| spring-boot-jdbc-example | 现有 spring-boot-starter + persistence-jdbc |

### 9.3 命名对齐

本组件规定的命名约束适用于所有后续组件落地：

- 新模块 artifactId 遵循 §3.2.2 命名规则。
- 新模块 pom 加入 `helm-bom` `<dependencyManagement>`。
- 新模块若有 SPI，对应 ContractTest 基类放 `helm-runtime-testkit`。
- 新模块若有 example，放 `examples/<scenario>-example/`，配 README。
- 新 `HelmException` 子类的 code 在 `ErrorCode` 注册（#11 §3.3）。

### 9.4 时序对齐

本组件（#10）是横向贯穿组件，可与任意组件并行推进。但建议落地顺序：

1. **先做**：license、groupId 迁移、Maven wrapper、CI、CHANGELOG、CONTRIBUTING（M10 阻塞项，无依赖）。
2. **同步做**：helm-bom、testkit 迁移、模块 README、adapter guides（不依赖其他组件）。
3. **补缺 examples**：标"现有"的可立即做；标组件编号的等对应组件落地。
4. **发布前做**：publishing checklist、consumer sample、release workflow（需要 Sonatype namespace 批准，可能需 1-2 周）。

建议本组件作为 M10 的核心 slice，先完成 license / CI / groupId / wrapper / CHANGELOG / CONTRIBUTING / testkit 七项，关闭 roadmap 第 7 节两个 blocker；其余 examples / guides / publishing 可分批补齐。
