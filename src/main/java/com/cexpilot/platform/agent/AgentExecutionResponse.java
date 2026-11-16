package com.cexpilot.platform.agent;

import com.cexpilot.platform.execution.ExecutionResult;
import java.time.Instant;
import java.time.ZoneId;

/** 底座执行结果及关联标识；业务提交完成后可自行组装 HTTP 响应。 */
public record AgentExecutionResponse(ExecutionResult result, String conversationId, String turnId, String traceId,
                                     Instant requestStartedAt, ZoneId userZone, long durationMs) {}
