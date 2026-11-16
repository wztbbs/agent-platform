package com.cexpilot.platform.context.session;

import com.cexpilot.platform.agent.AgentInput;
import com.cexpilot.platform.context.evidence.EvidenceSession;
import com.cexpilot.platform.execution.ExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;

/** 会话端口返回的内部执行材料；preparedInput 由业务组装，preset 为可选的服务端固定回复。 */
public record AgentExecutionSession(String history, AgentInput preparedInput, EvidenceSession evidenceSession,
                                     JsonNode routingState, ExecutionResult preset) {}
