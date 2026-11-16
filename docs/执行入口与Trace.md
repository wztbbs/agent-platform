# 执行入口、会话装配与 Trace

适用版本：agent-platform 0.3.0（包名迁移见[包结构与迁移](包结构与迁移.md)）。宿主通过 AgentExecutionService 发起执行；AgentDispatcher 和 AgentDispatchRequest 是内部装配后的分发层。

## 公共请求

```java
var request = new AgentExecutionRequest(
        question, conversationId, visitor, userZone, requestStartedAt);
var response = executions.execute(request, event -> {
    switch (event.type()) {
        case STARTED -> sendMeta(event.conversationId(), event.traceId(), event.turnId());
        case PROGRESS -> sendProgress(event.text());
        case DELTA -> sendDelta(event.text());
        case AGENT_SELECTED -> rememberAgent(event.text());
    }
});
// response.result() 用于业务提交，再组装自己的 HTTP 或 SSE 完成响应。
```

五个常用字段：问题、会话 ID、可信主体 ID、用户 ZoneId、Instant 请求接收时间。另有七参数构造，可补充 BusinessInput 和 upstreamTraceId。前者只传业务原始输入，后者仅关联上游链路；底座每次独立创建 traceId，不能把上游 ID 当成本次 ID 重用。

请求时间为空时底座 Clock 补齐；提供时沿用入口收到请求的时间。RequestContext.requestTime、Trace.request_started_at 和持久化 Run.requestTime 使用同一时刻，恢复时不重新读取时钟解释“今天”。会话 ID 为空时缺省生成 UUID；需要所有权、串行轮次或父轮次校验的业务，先登记业务会话和轮次，再提交执行请求。

公共请求不携带 history、routingState、EvidenceSession、TraceSink 或 Consumer 回调，也不要求业务构建 AgentRunContext。AgentInput 仍作为**底座内部已装配输入**：它不是公共 HTTP 请求。

## 两类上下文端口

| 端口 | 调用时机 | 职责 |
| --- | --- | --- |
| AgentSessionProvider | 路由之前 | 按请求读取历史、路由状态和证据会话，组装内部 AgentInput；可返回固定回复 |
| AgentContextProvider | 路由／目标 Agent 执行时 | 将已取得的数据投影为 Router 模型上下文、完整会话视图和 Agent 私有输入 |

AgentSessionProvider.resolve 返回 AgentExecutionSession。traceAttributes 提供来源标记、固定回复标记、可选提示词版本和业务 turnId；默认无业务依赖。没有实现时使用 BasicAgentSessionProvider，提供单轮 EvidenceSession，不自动实现业务历史存储。

端口是由底座调用的存取规则，不是要求业务每次把准备好的 history 等塞回公共请求。CEX 的实现是 CexAgentSessionProvider，BusinessInput 为已校验的轮次、澄清填写值及固定回复信息；PlanningContext 在端口内取得。QueryRunAdapter 负责把计划及 TaskScope 放入 QueryOutput，HTTP 入口无需重新推断规划范围。

AgentExecutionListener 是统一交互出口：STARTED、PROGRESS、DELTA、AGENT_SELECTED。STARTED 在 Trace 创建后、会话读取前发出；业务 traceAttributes 已提供 turnId 时可以立即返回，否则该早期事件 turnId 可为空，最终 response 返回证据会话 turnId。业务自己将事件适配为 SSE、WebSocket 或其他交互。结果由 execute 的返回值交付，业务提交完成后发送自己的 done。监听器可通过 accepts 筛选事件；未订阅 DELTA 时不启用答案模型的流式请求。

固定回复仍经过底座 Trace，但绕过路由和引擎。CEX 在业务轮次提交之后发送固定正文与 done，断连不会把已提交结果改为失败。

## Trace 归属

| 组件 | 职责 |
| --- | --- |
| AgentExecutionService | 创建本次执行现场、调用会话端口、分发执行、统一异常边界 |
| TraceService | 分配 traceId，开启主记录，保存当前请求的 SUCCESS／FAILED／WAITING、请求时间和用量 |
| TraceRepository / DbTraceSink | 通用 JDBC 主记录及事件读写；默认 sink 的事件写入故障记录完整日志并降级 |
| TraceRunCompletionListener | 业务恢复提交完成后，按原 traceId／请求时间完成 Trace；可重复调用 |
| 宿主 API / 页面 | 业务访问控制、展示和业务反馈关联 |

宿主默认不提供 sink；有特定审计通道时可通过自己的 TraceSink Bean 替换。替换后若不写 trace_event，恢复汇总会缺少实际事件，不能据此宣称已完整统计用量。

Trace 生命周期不依赖会话表、QueryOutput、监控规则或反馈表。CEX 保留 TraceController、PlannerTraceView、反馈和缓存统计；这些业务读取可以消费公共 Trace 数据，不参与 Trace 状态写入。BuildProperties（宿主构建 commit）由底座 TraceService 读取。

WAITING 不写 finished_at。恢复后的 duration_ms 从原请求接收时间算到完成，包含等待时间；初始 WAITING 响应的耗时仅到该片段返回。终态更新仅允许 RUNNING／WAITING，迟到的 WAITING 或重复恢复不能覆盖已经确定的终态。跨片段按已落 LLM_CALL 汇总 tokens 和 Router 次数，费用无法完整核实时为 null；缺事件不代表无调用。

运行等待和租约丢失是控制信号，继续传播，不记为业务失败。其他执行异常由统一入口记录错误与原始异常链，Trace 收尾再次失败时加入 suppressed，不覆盖主异常。Trace 主记录写入仍可能使请求失败；事件 sink 是尽力记录，两者不具备全量送达保证。

## Schema 与升级

底座 `platform/db/schema.sql` 拥有六张表：四张 platform_* 运行表，以及 ask_trace、trace_event。保留 Trace 历史表名和字段，便于既有回放接口及数据继续使用。CEX 的 db/schema.sql 仅保留业务表，feedback 也在业务侧。

新库由宿主显式加载公共 schema，再加载业务 schema。库不自动执行迁移。已有 0.1.x 的 Trace 库升级到 0.2.0 时，在停止旧版本后检查列是否存在，仅对尚未升级的库执行一次：

```bash
mysql [连接参数] < src/main/resources/platform/db/migrations/002_trace_request_context.sql
```

脚本增加 `request_started_at DATETIME(6)` 和 `upstream_trace_id VARCHAR(128)`。已有行保留，新增列为空；不伪造历史请求接收时间。若曾部分执行，先检查实际列，再只执行缺失的 ALTER。CREATE TABLE IF NOT EXISTS 不会补列。

request_started_at 明确存 UTC，输出带 Z；历史 created_at／finished_at 按 `platform.trace.database-time-zone`（默认 UTC）解释。CEX 将该属性设置为 DB_TIME_ZONE（默认 Asia/Shanghai），保持历史墙钟时间口径，不修改旧数据。

此次公共 API 修改不保留旧 AgentExecutionRequest 多参数构造：宿主升级代码使用新服务及端口。不要改用内部 AgentDispatchRequest 继续手工拼装对外入口。

## 本地验证

先 `mvn test-compile`，再运行 `mvn test -Dtest=AgentExecutionServiceTest,PlatformLibraryContextTest,PlatformSourceBoundaryTest`。覆盖最简请求、会话端口、统一事件、请求时间／上游关联、固定宿主独立运行、异常及用量、等待恢复和重复提交、schema 升级及默认 Bean 覆盖。不调用真实模型或线上服务。
