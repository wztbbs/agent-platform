package com.cexpilot.platform.run;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 业务运行接入契约。只保存 JSON 数据，不保存 Java 回调、对象类名或调用栈。
 * version 同时约束输入、检查点、私有输出及执行语义；变更格式必须变更版本。
 */
public interface AgentRunAdapter {
    /**
     * 返回适配器的稳定唯一标识，例如 cex-query；创建运行时保存，恢复时据此找回适配器。
     */
    String id();

    /**
     * 返回适配协议版本，覆盖输入、检查点、私有输出格式及执行语义。
     * 协议变更时必须更新版本；恢复时版本不匹配会拒绝执行，避免误读旧快照。
     */
    String version();

    /**
     * 判断是否能接管指定 Agent 的持久化运行；一个 Agent 最多只能匹配一个适配器。
     */
    boolean supports(ConversationAgent agent);

    /**
     * 提供该 Agent 的运行预算，包括模型调用次数、工具调用次数和执行超时等限制。
     * 协调器在创建运行时保存有效预算，恢复时继续使用已保存的预算及消耗记录。
     */
    QueryExecutionConfig limits(ConversationAgent agent);

    /**
     * 从首次请求上下文中提取业务私有输入，编码为可持久化的 JSON 快照。
     * 通用上下文由协调器保存；这里仅保存恢复业务所需的数据，不能保存回调或运行时对象。
     */
    JsonNode captureInput(ConversationAgent agent, AgentRunContext context);

    /**
     * 将业务输入快照还原为该 Agent 使用的私有输入类型，供协调器重建执行上下文。
     * 首次执行和后续恢复均使用此方法；没有私有输入时可返回 null。
     */
    AgentRunContext.PrivateInput restoreInput(ConversationAgent agent, JsonNode snapshot);

    /**
     * 根据当前输入及检查点执行一个片段：首次进入时启动业务，恢复进入时从已保存的进度继续。
     * 适配器决定如何调用引擎及读写业务检查点；返回 Completed 表示运行完成，Waiting 表示等待后续恢复。
     * 同一运行可能多次进入此方法，不能仅因重新进入就重复已完成的外部操作。
     */
    RunStep executeSegment(ConversationAgent agent, RunSegment segment);

    /**
     * 提供该 Agent 的工具审批模式：默认逐调用确认，业务可显式选择整份方案审批。
     * 模式由服务端确定并随运行保存；恢复时校验一致，不能由模型入参降级。
     */
    default com.cexpilot.platform.policy.approval.ToolApprovalMode toolApprovalMode(ConversationAgent agent) {
        return com.cexpilot.platform.policy.approval.ToolApprovalMode.PER_CALL;
    }

    /**
     * 将执行结果中的业务私有输出编码为 JSON，随最终结果保存，供重启后还原及业务提交使用。
     * 有私有输出的业务必须覆盖此方法；默认只接受 null，禁止隐式序列化任意业务对象。
     */
    default JsonNode captureOutput(AgentOutput output) {
        if (output != null) throw new IllegalArgumentException("该运行适配器未声明私有输出编码");
        return null;
    }
    /**
     * 将已保存的私有输出 JSON 还原为明确的业务输出类型，用于结果读取和业务提交。
     * 有私有输出的业务必须覆盖此方法；默认只接受空快照，不能按用户提供的 Java 类名反序列化。
     */
    default AgentOutput restoreOutput(JsonNode snapshot) {
        if (snapshot != null && !snapshot.isNull()) throw new IllegalArgumentException("该运行适配器未声明私有输出解码");
        return null;
    }

    /**
     * 将已落库的最终结果提交到业务状态，例如更新会话轮次和任务结果；成功或失败的运行均可能调用。
     * 崩溃后可能重复投递，必须以 runId 保证幂等；不得在此重新执行工具或生成新计划。
     * 返回 true 表示业务提交已完成，false 表示暂未具备提交条件、由底座稍后重试。
     * 默认直接返回 true，适用于无额外业务提交的适配器。
     */
    default boolean commit(RunCompletion completion) { return true; }
}
