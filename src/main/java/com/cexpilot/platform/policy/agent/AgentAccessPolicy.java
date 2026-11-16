package com.cexpilot.platform.policy.agent;

import com.cexpilot.platform.agent.ConversationAgent;
/** 应用控制路由可见性及实际执行准入；同一规则也用于恢复执行。 */
public interface AgentAccessPolicy { boolean allows(ConversationAgent agent, String visitor); }
