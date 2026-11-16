package com.cexpilot.platform.context.session;

import com.cexpilot.platform.agent.AgentExecutionRequest;
import com.cexpilot.platform.context.evidence.EvidenceSession;
import com.cexpilot.platform.execution.RequestContext;
import java.util.UUID;

/** 无业务依赖的单轮会话；需要历史或业务轮次控制时由宿主提供 AgentSessionProvider。 */
public final class BasicAgentSessionProvider implements AgentSessionProvider {
    public AgentExecutionSession resolve(AgentExecutionRequest request, String traceId, RequestContext context) {
        return new AgentExecutionSession(null, null,
                new EvidenceSession(request.conversationId(), UUID.randomUUID().toString()), null, null);
    }
}
