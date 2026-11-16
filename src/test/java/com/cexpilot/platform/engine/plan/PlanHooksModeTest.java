package com.cexpilot.platform.engine.plan;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.execution.ExecutionFailure;
import com.cexpilot.platform.execution.ExecutionResult;
import com.cexpilot.platform.execution.TurnCounters;
import com.cexpilot.platform.llm.ChatResponse;
import com.cexpilot.platform.llm.LlmCallService;
import com.cexpilot.platform.llm.LlmUsage;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.run.RunLeaseLostException;
import com.cexpilot.platform.run.RunSuspended;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanHooksModeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutionResult expected = new ExecutionResult("完成", JSON.createArrayNode(), 0, 0, 0, 0);

    private PlanDrivenAgent agent(PlanHooks hooks) {
        return new PlanDrivenAgent() {
            public AgentDefinition definition() { return new AgentDefinition("test_plan", "test_plan", "模式测试"); }
            public String promptVersion() { return "v1"; }
            public PlanHooks planHooks() { return hooks; }
            public ExecutionResult execute(AgentRunContext run) { throw new AssertionError("应由 PlanEngine 分发"); }
        };
    }

    private AgentRunContext run() {
        var run = mock(AgentRunContext.class);
        when(run.traceId()).thenReturn("trace");
        return run;
    }

    @Test void selfDrivenModeExecutesOnceWithoutEnteringManagedPlanningOrComplete() {
        var calls = new AtomicInteger();
        var hooks = new PlanHooks() {
            public boolean selfDriven() { return true; }
            public ExecutionResult executeSelfDriven(AgentRunContext run) { calls.incrementAndGet(); return expected; }
            public ExecutionResult complete(PlanCompletion completion) { throw new AssertionError("不应进入 complete"); }
        };
        var prompts = mock(PromptStore.class);
        var llm = mock(LlmCallService.class);
        assertSame(expected, new PlanEngine(null, prompts, llm, null, null).execute(agent(hooks), run()));
        assertEquals(1, calls.get());
        verifyNoInteractions(prompts, llm);
    }

    @Test void managedModeUsesPlanningAndCompleteWithoutCallingSelfDrivenExecution() {
        var hooks = new ManagedHooks();
        var prompts = mock(PromptStore.class);
        when(prompts.render(anyString(), anyMap())).thenReturn("system");
        var llm = mock(LlmCallService.class);
        when(llm.call(anyString(), anyString(), anyList(), anyString(), any(), anyList()))
                .thenReturn(new ChatResponse("{}", List.of(), 0, 0));
        assertFalse(hooks.selfDriven());
        assertSame(expected, new PlanEngine(null, prompts, llm, null, null).execute(agent(hooks), run()));
        assertEquals(1, hooks.completions);
        verify(llm).call(anyString(), anyString(), anyList(), anyString(), any(), anyList());
    }

    @Test void registrationRejectsSelfDrivenModeWithoutExecutionImplementation() {
        var hooks = new PlanHooks() { public boolean selfDriven() { return true; } };
        var failure = assertThrows(IllegalArgumentException.class, () -> new AgentRegistry(List.of(agent(hooks))));
        assertTrue(failure.getMessage().contains("selfDriven=true"));
        assertTrue(failure.getMessage().contains("executeSelfDriven"));
    }

    @Test void implementingSelfDrivenExecutionDoesNotImplicitlySelectSelfDrivenMode() {
        var hooks = new PlanHooks() {
            public ExecutionResult executeSelfDriven(AgentRunContext run) { throw new AssertionError("不可隐式切换模式"); }
        };
        var failure = assertThrows(IllegalArgumentException.class, () -> new AgentRegistry(List.of(agent(hooks))));
        assertTrue(failure.getMessage().contains("selfDriven=false"));
        assertTrue(failure.getMessage().contains("stage"));
    }

    @Test void registrationRejectsMissingHooks() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRegistry(List.of(agent(null))));
    }

    @Test void registrationValidatesConfigurationWithoutExecutingBusiness() {
        var selfDriven = new PlanHooks() {
            public boolean selfDriven() { return true; }
            public ExecutionResult executeSelfDriven(AgentRunContext run) { throw new AssertionError("启动不能执行业务"); }
        };
        assertDoesNotThrow(() -> new AgentRegistry(List.of(agent(selfDriven))));
        var managed = new ManagedHooks();
        assertDoesNotThrow(() -> new AgentRegistry(List.of(agent(managed))));
        assertEquals(0, managed.completions);
    }

    @Test void controlSignalsAndCountedFailuresPropagateUnchanged() {
        var failure = new ExecutionFailure(new IllegalStateException("失败"), new TurnCounters(1, 0, 0, 0, 0, 0, List.of()));
        for (var control : List.of(new RunSuspended("call"), new RunLeaseLostException("接管"), failure)) {
            var hooks = new PlanHooks() {
                public boolean selfDriven() { return true; }
                public ExecutionResult executeSelfDriven(AgentRunContext run) { throw control; }
            };
            assertSame(control, assertThrows(RuntimeException.class,
                    () -> new PlanEngine(null, null, null, null, null).execute(agent(hooks), run())));
        }
    }

    @Test void nullSelfDrivenResultFailsInsteadOfFallingBackToManagedPlanning() {
        var hooks = new PlanHooks() {
            public boolean selfDriven() { return true; }
            public ExecutionResult executeSelfDriven(AgentRunContext run) { return null; }
        };
        var failure = assertThrows(NullPointerException.class,
                () -> new PlanEngine(null, null, null, null, null).execute(agent(hooks), run()));
        assertTrue(failure.getMessage().contains("必须返回最终结果"));
    }

    private class ManagedHooks implements PlanHooks {
        int completions;
        public String stage() { return "agent_plan"; }
        public String planningPrompt() { return "plan_agent"; }
        public Map<String, String> planningVars() { return Map.of(); }
        public ObjectNode planningInput(AgentRunContext run) { return JSON.createObjectNode(); }
        public int maxAttempts() { return 1; }
        public PlanVerdict validate(JsonNode output, AgentRunContext run, List<LlmUsage> usages) {
            return PlanVerdict.compiled("compiled-plan");
        }
        public String repairMessage(String error) { return error; }
        public ExecutionResult exhausted(PlanCompletion completion) { throw new AssertionError("不应耗尽"); }
        public ExecutionResult complete(PlanCompletion completion) {
            assertEquals("compiled-plan", completion.compiled());
            completions++;
            return expected;
        }
        public ExecutionResult executeSelfDriven(AgentRunContext run) { throw new AssertionError("引擎模式不能调用自驱动入口"); }
    }
}
