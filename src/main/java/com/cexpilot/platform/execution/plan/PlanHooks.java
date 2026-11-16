package com.cexpilot.platform.execution.plan;

import com.cexpilot.platform.agent.AgentRunContext;

import com.cexpilot.platform.llm.LlmUsage;
import com.cexpilot.platform.execution.ExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Plan 引擎的业务回调。引擎拥有规划循环（提示词渲染、LLM 调用、修复重试、用量聚合、失败包装），
 * 业务方注入规划方言（stage/提示词/输入/校验）与结果组装；自驱动模式由业务自行完成规划和执行。
 * 回调只回答执行语义（计划合法吗、结果怎么组装），业务流程（草稿确认、任务续期）不进入回调。
 */
public interface PlanHooks {

    /** 一次规划校验的结论：编译产物（业务自有类型）/ 可修复（带反馈）/ 终态降级（直接作为本轮结果）。 */
    record PlanVerdict(Object compiled, String repairFeedback, ExecutionResult terminal) {
        public static PlanVerdict compiled(Object compiled) { return new PlanVerdict(compiled, null, null); }
        public static PlanVerdict repair(String feedback) { return new PlanVerdict(null, feedback, null); }
        public static PlanVerdict terminal(ExecutionResult result) { return new PlanVerdict(null, null, result); }
    }

    /** 校验通过或尝试耗尽时的组装上下文：运行上下文、编译产物、已累计的模型用量与规划调用数（仅用于引擎内置规划循环）。 */
    record PlanCompletion(AgentRunContext run, Object compiled, List<LlmUsage> usages, int plannerCalls) {}

    /**
     * 声明是否由业务自行管理完整规划和执行流程；默认 false，使用引擎内置规划循环。
     * 此方法只返回稳定的模式配置，不调用模型、不执行工具，也不根据本轮结果临时切换模式。
     * true 时必须实现 executeSelfDriven；false 时必须实现内置规划循环所需的回调及 complete。
     * Agent 注册时校验所选模式的必需方法，配置错误直接阻止启动。
     */
    default boolean selfDriven() { return false; }

    /**
     * 自驱动模式的唯一执行入口：自行完成规划、修复、执行及回答，返回非 null 的最终结果。
     * 模型用量由业务累计到结果或 ExecutionFailure；等待和租约丢失信号继续上抛给运行协调器。
     * 引擎不会再调用内置规划循环或 complete；默认实现只用于明确拒绝错误调用。
     */
    default ExecutionResult executeSelfDriven(AgentRunContext run) {
        throw new UnsupportedOperationException("自驱动模式必须实现 executeSelfDriven");
    }

    /** LLM 调用 stage（trace 事件名与预算计量口径，如 agent_plan / monitor_planner）。 */
    default String stage() { throw new UnsupportedOperationException("引擎内置规划循环未启用"); }

    /** 规划系统提示词模板名。 */
    default String planningPrompt() { throw new UnsupportedOperationException("引擎内置规划循环未启用"); }

    /** 规划提示词的插值变量（业务目录、工具清单等）。 */
    default Map<String, String> planningVars() { throw new UnsupportedOperationException("引擎内置规划循环未启用"); }

    /** 规划 user 输入 JSON。 */
    default ObjectNode planningInput(AgentRunContext run) { throw new UnsupportedOperationException("引擎内置规划循环未启用"); }

    /** 规划前的用户进度提示等副作用；默认无。 */
    default void beforePlanning(AgentRunContext run) {}

    /** 含首次在内的最大规划尝试次数。 */
    default int maxAttempts() { throw new UnsupportedOperationException("引擎内置规划循环未启用"); }

    /** 校验一次规划输出 JSON；失败按需落业务的校验事件。usages 为已累积的模型用量（含本次规划调用），终态结果组装时取用。 */
    default PlanVerdict validate(JsonNode planOutput, AgentRunContext run, java.util.List<LlmUsage> usages) {
        throw new UnsupportedOperationException("引擎内置规划循环未启用");
    }

    /** 模型输出无法解析为 JSON 或违反输出协议时的通知（业务可落校验事件）；默认无操作。 */
    default void planRejected(String rawContent, String error, AgentRunContext run) {}

    /** 校验失败的修复反馈文本（追加为 user 消息供下一轮修复）。 */
    default String repairMessage(String validationError) { throw new UnsupportedOperationException("引擎内置规划循环未启用"); }

    /** 尝试耗尽时的降级结果。 */
    default ExecutionResult exhausted(PlanCompletion completion) { throw new UnsupportedOperationException("引擎内置规划循环未启用"); }

    /** 规划阶段预算耗尽的降级结果；默认与尝试耗尽一致（预算不计量的 stage 不会触发）。 */
    default ExecutionResult budgetExhausted(PlanCompletion completion, com.cexpilot.platform.execution.QueryBudget.Exhausted exhausted) {
        return exhausted(completion);
    }

    /**
     * 引擎内置规划循环校验通过后，使用已生成的规划产物完成执行与结果组装。
     * 仅 selfDriven=false 的实现需要覆盖；自驱动模式不会调用此方法。
     */
    default ExecutionResult complete(PlanCompletion completion) {
        throw new UnsupportedOperationException("引擎规划模式必须实现 complete");
    }
}
