# Agent Platform

独立维护的 Java Agent 应用底座，提供 ReAct／Plan 执行、统一请求入口、会话装配、Trace 存储、上下文扩展、工具调用控制、方案审批、持久化运行、检查点恢复、模型调用和审计端口。

当前制品是 `com.cexpilot:agent-platform:0.3.0`，普通 JAR，Java 17／Spring Boot 3.3.5。独立继承公开的 Spring Boot 构建父 POM，不依赖 CEXPilot 的父 POM、源码或业务表。保留 `com.cexpilot.platform` 根包及 groupId；0.3.0 按职责重组子包，宿主升级时需要更新 import 并重新编译。

本工程没有 Application 主类，不单独启动服务。宿主应用通过 Maven 依赖加载；不包含 CEX／客户 Agent、业务配置、Demo、页面、交易所／邮件 SDK 或凭证。

## 包结构

根包 `com.cexpilot.platform` 下按职责划分 9 个一级包：

```text
agent           Agent 定义、注册、路由与宿主执行入口
execution       ReAct／Plan／DAG 引擎及执行预算
run             持久化运行、检查点、等待与恢复
tool            工具定义、注册、适配与实际执行
llm             模型协议、客户端与调用服务
policy          Agent／工具授权与方案审批
context         上下文、会话接入、Prompt 与证据接口
observability   Trace、用量与诊断
configuration   Spring 显式装配与自动配置
```

跨模块使用的类型仍按职责归属组织，不设通用 common／shared 包。详细边界和旧包迁移表见 [包结构与迁移](docs/包结构与迁移.md)。

## 本地构建与验证

在本工程根目录执行：

```bash
mvn test-compile
mvn test -Dtest=AgentExecutionServiceTest,PlatformLibraryContextTest,PlatformSourceBoundaryTest,PlanHooksModeTest,LlmCallServiceTest,LlmDiagnosticsTest,LlmJsonTest,LlmRequestParametersTest,LlmUsageTest
mvn install -DskipTests
```

install 将 JAR 和独立 POM 放入本机 Maven 仓库。之后任何业务工程都可以通过以下依赖编译，无需把本工程源码放在旁边：

```xml
<dependency>
    <groupId>com.cexpilot</groupId>
    <artifactId>agent-platform</artifactId>
    <version>0.3.0</version>
</dependency>
```

不默认执行全量测试，不擅自调用真实模型或线上业务。非 CEX 测试宿主使用脚本模型和 H2，覆盖真实的公共注册、路由、引擎及持久化运行。

## 接入

宿主通过 `@Import(PlatformConfiguration.class)` 引入组件，提供自己的 Agent／工具、数据库、normalLlmClient 和 ToolEvidenceWriter。底座默认提供 UTC Clock 与数据库 TraceSink，宿主可用同类型 Bean 替换；业务无需为每次请求创建 sink。数据库脚本由宿主显式初始化 `classpath:platform/db/schema.sql`；库不会自动创建数据库或执行业务迁移。

公共请求和会话端口见 [执行入口与 Trace](docs/执行入口与Trace.md)。0.3.0 的包名变更见 [包结构与迁移](docs/包结构与迁移.md)。0.2.0 引入的 Trace 两列迁移仍适用于尚未升级的存量数据库；已完成迁移的库无需重复执行。

详细要求见 [接入与发布](docs/接入与发布.md)、[Plan Agent 接入](docs/PlanAgent接入.md)。

## 部署服务器如何获取依赖

Git 源码位于 `git@github.com:wztbbs/agent-platform.git`，main 分支；目前没有配置远程 Maven 私服。服务器不能从 Maven Central 下载本项目，应先安装指定版本制品，再构建业务应用。

```bash
# 在已构建本工程的机器生成发布包。
mvn package -DskipTests
python3 scripts/package_release.py
```

将 `target/agent-platform-0.3.0-release.zip` 传至服务器，在临时目录解压后，以构建业务应用的同一用户执行：

```bash
sha256sum -c SHA256SUMS
mvn install:install-file \
  -Dfile=agent-platform-0.3.0.jar \
  -DpomFile=agent-platform-0.3.0.pom
```

另一种方式是在服务器单独取得本工程源码并执行 `mvn install -DskipTests`。两种方式均不要求本工程与业务工程相邻。

正式建立 Maven 私服后，将每个版本发布至私服，业务只需固定依赖版本并配置仓库。不要覆盖已发布版本，也不要在各业务工程复制底座源码再各自维护。
