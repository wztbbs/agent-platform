package com.cexpilot.platform.agent;

/** 一次执行的统一交互事件；text 对应进度、答案增量或已选 Agent 路由。 */
public record AgentExecutionEvent(Type type, String conversationId, String turnId, String traceId, String text) {
    public enum Type { STARTED, PROGRESS, DELTA, AGENT_SELECTED }
}
