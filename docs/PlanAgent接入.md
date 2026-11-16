# Plan Agent 接入

Plan Agent 有两种 Java 接入模式，由 `PlanHooks.selfDriven()` 显式声明。该方法只读取稳定的模式配置，不调用模型、不执行工具，也不根据某一轮结果切换模式。`PlanDrivenAgent.planHooks()` 应返回具有稳定模式的回调实现。

| 接入方式 | selfDriven() | 规划循环 | 业务实现 |
| --- | --- | --- | --- |
| YAML 声明式 Agent | 底座默认 false | PlanEngine | 配置 YAML、业务 Prompt 和工具绑定，不编写 Java hooks |
| Java 业务规划方言 | 默认 false | PlanEngine | 输入、提示词、输出校验、修复反馈及 complete |
| Java 自驱动业务 | 显式 true | 业务专用执行器 | executeSelfDriven，返回完整 ExecutionResult |

两种模式都可以由 RunCoordinator 管理运行，审批、工具权限和恢复机制不由 selfDriven 开关决定。需要持久化运行的自定义业务仍须提供 AgentRunAdapter，说明业务输入输出、检查点和幂等结果提交。

## 使用引擎内置规划

适用于“调用模型 → 校验 → 修复重试”的规划流程，例如 Monitor。省略 selfDriven 或明确返回 false。

必须实现以下方法，缺少任何一项均在 AgentRegistry 注册时拒绝启动：

| 方法 | 功能 |
| --- | --- |
| stage | 模型调用的 trace 阶段名 |
| planningPrompt | 系统提示词模板名 |
| planningVars | 提示词变量 |
| planningInput | 本轮规划输入 |
| maxAttempts | 包含首轮的最大规划尝试次数 |
| validate | 校验模型输出，返回编译产物、修复反馈或终态结果 |
| repairMessage | 格式化修复反馈 |
| exhausted | 规划尝试耗尽后的降级结果 |
| complete | 使用已通过校验的规划产物执行后续逻辑并生成最终结果 |

`beforePlanning`、`planRejected` 为可选通知；`budgetExhausted` 默认调用 exhausted，也可覆盖。

validate 返回的 PlanVerdict 仍可表示 compiled、repair 或 terminal，这是内置规划循环的校验协议。它不承担模式选择。complete 接收引擎生成的 PlanCompletion，包含规划产物、模型用量及规划调用次数；自驱动模式不会调用 complete。

## 使用自驱动执行

适用于已有专用规划／执行状态机的业务，例如 Query。实现方式：

```java
@Override
public boolean selfDriven() {
    return true;
}

@Override
public ExecutionResult executeSelfDriven(AgentRunContext run) {
    return runtime.execute(/* 根据业务组装输入 */);
}
```

引擎只调用 executeSelfDriven，不再进入内置规划循环，也不调用 complete。返回值必须是最终 ExecutionResult，不能通过 null 表示回退到另一模式。不需要实现 stage、validate、complete 等内置规划回调。

自驱动业务负责在结果中累计真实模型及工具用量；执行失败时通过 ExecutionFailure 携带已发生的用量。RunSuspended、RunLeaseLostException 和已包装的 ExecutionFailure 会原样传递，不能转换为规划修复。恢复能力仍依赖业务检查点及统一工具网关。

## 启动校验与迁移

注册 Java PlanDrivenAgent 时，AgentRegistry 使用 PlanHooksValidator 检查所选模式的必需方法是否已覆盖。校验只检查模式及方法实现，不调用模型或业务执行方法；参数内容和业务语义仍由相应实现校验。PlanEngine 直接调用入口也执行同样的契约检查。

- 声明 selfDriven=true 却未实现 executeSelfDriven：启动失败，提示缺少 executeSelfDriven。
- 默认 false 却只实现自驱动执行方法：仍视为引擎模式，启动时报告缺失的规划回调。
- executeSelfDriven 返回 null：执行失败，不回退、不重复进入其他流程。

旧的 planSelfDriven(run) 已移除。迁移时改为 selfDriven 返回 true，将原方法中完整的业务执行逻辑移入 executeSelfDriven，并直接返回 ExecutionResult，去掉 PlanVerdict.terminal 包装。Query 已按此方式迁移，其 DagRuntime 的规划、修复、执行及回答逻辑保持原样。
