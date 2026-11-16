package com.cexpilot.platform.context.session;

import com.cexpilot.platform.agent.AgentExecutionRequest;
import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.observability.TraceAttributes;

/** 底座调用此端口获取历史、业务私有输入、路由状态与证据会话；宿主决定身份及具体存取规则。 */
public interface AgentSessionProvider {
    AgentExecutionSession resolve(AgentExecutionRequest request, String traceId, RequestContext context);
    /** 仅提供业务来源及固定回复元信息，不创建 Trace，也不执行模型或工具。 */
    default TraceAttributes traceAttributes(AgentExecutionRequest request) { return TraceAttributes.DEFAULT; }
}
