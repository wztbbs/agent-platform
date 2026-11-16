package com.cexpilot.platform.agent;

/** 宿主将执行事件适配为 SSE、WebSocket 或其他交互，不向底座传递传输层对象。 */
@FunctionalInterface
public interface AgentExecutionListener {
    AgentExecutionListener NONE = new AgentExecutionListener() {
        public void onEvent(AgentExecutionEvent event) {}
        public boolean accepts(AgentExecutionEvent.Type type) { return false; }
    };

    void onEvent(AgentExecutionEvent event);

    /** 仅订阅所需事件；未订阅 DELTA 时不启用模型答案流式调用，保留同步请求语义。 */
    default boolean accepts(AgentExecutionEvent.Type type) { return true; }
}
