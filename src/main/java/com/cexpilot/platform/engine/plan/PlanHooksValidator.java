package com.cexpilot.platform.engine.plan;

import com.cexpilot.platform.agent.AgentRunContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 校验所选执行模式的必需回调；只检查方法实现，不发起规划或业务执行。 */
public final class PlanHooksValidator {
    private PlanHooksValidator() {}

    public static void validate(PlanHooks hooks) {
        if (hooks == null) throw new IllegalArgumentException("Plan Agent 必须提供 PlanHooks");
        if (hooks.selfDriven()) {
            requireOverride(hooks, "executeSelfDriven", AgentRunContext.class);
            return;
        }
        requireOverride(hooks, "stage");
        requireOverride(hooks, "planningPrompt");
        requireOverride(hooks, "planningVars");
        requireOverride(hooks, "planningInput", AgentRunContext.class);
        requireOverride(hooks, "maxAttempts");
        requireOverride(hooks, "validate", JsonNode.class, AgentRunContext.class, List.class);
        requireOverride(hooks, "repairMessage", String.class);
        requireOverride(hooks, "exhausted", PlanHooks.PlanCompletion.class);
        requireOverride(hooks, "complete", PlanHooks.PlanCompletion.class);
    }

    private static void requireOverride(PlanHooks hooks, String method, Class<?>... parameters) {
        try {
            if (hooks.getClass().getMethod(method, parameters).getDeclaringClass() == PlanHooks.class)
                throw new IllegalArgumentException(hooks.getClass().getName() + " 的 selfDriven=" + hooks.selfDriven()
                        + "，必须实现 " + method);
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException("PlanHooks 校验器与接口不一致: " + method, impossible);
        }
    }
}
