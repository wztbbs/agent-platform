package com.cexpilot.platform.agent;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 对外执行请求：问题、会话、可信身份、用户时区和入口接收时间。
 * businessInput 为可选业务数据，upstreamTraceId 仅关联上游链路；均不承担内部执行上下文装配。
 * 时间为空时由底座 Clock 补齐，历史、路由状态、Trace、流式适配与证据会话不由调用方拼装。
 */
public record AgentExecutionRequest(String question, String conversationId, String visitor,
                                    ZoneId userZone, Instant requestStartedAt,
                                    BusinessInput businessInput, String upstreamTraceId) {
    public AgentExecutionRequest(String question, String conversationId, String visitor,
                                 ZoneId userZone, Instant requestStartedAt) {
        this(question, conversationId, visitor, userZone, requestStartedAt, null, null);
    }
}
