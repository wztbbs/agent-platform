# Agent Platform 开发规范

- 本工程是独立 Maven 普通 JAR，不依赖 CEXPilot 的父 POM、源码、业务 SDK、配置或数据库表。
- 保留现有 com.cexpilot.platform 包名及 com.cexpilot:agent-platform 坐标；包名不代表依赖 CEX 应用。
- 所有 Java if、else if、else 分支均必须使用 {}，即使只有一行。修改方法时补齐其条件分支花括号。
- 验证改动先执行 mvn test-compile，再按类名执行相关单元测试；只有用户明确要求或 CI 场景才跑全量 mvn test。
- 测试使用脚本模型、H2 或本地模拟服务，不擅自调用真实模型、交易所或线上业务。
- 不在库中加入 application.yml、客户业务 Agent／工具、凭证、业务表或启动主类。
- 发布新版本时更新 pom.xml；不得覆盖已发布版本。宿主应用自行选择升级时间。

- 对外执行使用 AgentExecutionService／AgentExecutionRequest；AgentDispatchRequest 只供底座内部装配，不要求宿主拼装 TraceSink、history 或内部上下文。
- Trace 生命周期、存储和 ask_trace／trace_event DDL 归底座；宿主的会话、业务反馈和展示留在业务工程。DDL 变更同时更新完整 schema 与显式升级脚本，不能靠 CREATE IF NOT EXISTS 补列。
