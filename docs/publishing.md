# Helm 发布手册

发布 Helm artifacts 到 Maven Central 的流程与检查项。pre-1.0 阶段尚未正式发布；本文档为发布就绪手册。

## 前置

- JDK 21
- GPG 密钥（用于签名 artifacts）
- Sonatype Central Portal 账号 + user token
- `~/.m2/settings.xml` 配置 server：

```xml
<servers>
  <server>
    <id>central</id>
    <username>central-portal-user-token-username</username>
    <password>central-portal-user-token-password</password>
  </server>
</servers>
```

## 版本策略

| 版本 | 兼容性 |
| --- | --- |
| `0.x.0-SNAPSHOT` | 开发中 |
| `0.x.0` | 发布版（去 `-SNAPSHOT`） |
| 跨 `0.minor` | pre-1.0：允许破坏 public/SPI，但被移除 API 必须先 `@Deprecated(forRemoval=true, since="0.x")` 一个 minor |
| `1.0.0` 起 | 仅 major 版本允许破坏 |

## 发布 profile

根 `pom.xml` 加 `release` profile（GPG 签名 + sources/javadoc jar）：

```xml
<profile>
  <id>release</id>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-source-plugin</artifactId>
        <executions><execution><id>attach-sources</id><goals><goal>jar-no-fork</goal></goals></execution></executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-javadoc-plugin</artifactId>
        <executions><execution><id>attach-javadocs</id><goals><goal>jar</goal></goals></execution></executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-gpg-plugin</artifactId>
        <executions><execution><id>sign-artifacts</id><phase>verify</phase><goals><goal>sign</goal></goals></execution></executions>
      </plugin>
      <plugin>
        <groupId>org.sonatype.central</groupId>
        <artifactId>central-publishing-maven-plugin</artifactId>
        <extensions>true</extensions>
      </plugin>
    </plugins>
  </build>
</profile>
```

## 发布步骤

1. `mvn verify` 全绿（compile + test + spotless）。
2. 更新 `CHANGELOG.md`，列出新版本变更。
3. 去版本号 `-SNAPSHOT`（`mvn versions:set -DnewVersion=0.x.0`）。
4. `mvn clean deploy -P release`（签名 + 上传 staging）。
5. Sonatype Central Portal：close + release staging repository。
6. 等待同步（~15-30 分钟），验证 Maven Central 可拉取：

   ```bash
   mvn dependency:get -Dartifact=io.agent.helm:helm-core:0.x.0
   ```

7. Git tag `v0.x.0` + push tag。
8. bump 到下一个 `0.x+1.0-SNAPSHOT` + commit。

## 发布检查项

- [ ] `mvn verify` 全绿（20 模块）
- [ ] `CHANGELOG.md` 更新
- [ ] 版本去 `-SNAPSHOT`
- [ ] GPG 签名（每个 jar + pom）
- [ ] sources jar + javadoc jar
- [ ] BOM 已发布
- [ ] staging close + release
- [ ] Maven Central 验证可拉取
- [ ] GitHub release tag
- [ ] bump 下一个 `-SNAPSHOT`

## 模块发布范围

`helm-bom` 列出全部发布模块（core/engine/runtime + 8 adapter + memory-semantic/client/observability-opentelemetry）。testkit（合约测试基类）随 `helm-core` test-jar 发布，不单独列出。

## API 兼容性

发布后破坏变更（删除 public/SPI 类型、改签名、改 enum 值）必须：
1. 在 PR 标注 Breaking Change。
2. 被移除 API 先 `@Deprecated(forRemoval=true)` 一个 minor。
3. 更新 `docs/contracts/api-baseline.md`（japicmp 报告）。
4. 所有 in-tree adapter 同 PR 更新通过。
