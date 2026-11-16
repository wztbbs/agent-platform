package com.cexpilot.platform.observability;

import com.cexpilot.platform.agent.AgentExecutionRequest;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.llm.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.UUID;

/** Trace 生命周期的唯一入口：创建关联标识、完成当前片段、记录失败以及恢复后的最终状态。 */
@Service
public class TraceService {
    public record Handle(String id, Instant requestStartedAt) {}
    private final TraceRepository repository;
    private final LlmConfig models;
    private final Clock clock;
    private final String buildCommit;

    @Autowired
    public TraceService(TraceRepository repository, LlmConfig models, Clock clock,
                        ObjectProvider<BuildProperties> build) {
        this(repository, models, clock, build.getIfAvailable() == null ? null : build.getIfAvailable().get("commit"));
    }

    public TraceService(TraceRepository repository, LlmConfig models, Clock clock, String buildCommit) {
        this.repository = repository;
        this.models = models;
        this.clock = clock;
        this.buildCommit = buildCommit;
    }

    public Handle start(AgentExecutionRequest request, TraceAttributes attributes, String promptVersion) {
        var handle = new Handle(UUID.randomUUID().toString(), request.requestStartedAt());
        repository.startTrace(handle.id(), request.conversationId(), request.question(),
                attributes.staticReply() ? null : models.getNormal().getModel(),
                attributes.promptVersion() == null ? promptVersion : attributes.promptVersion(),
                request.visitor(), buildCommit, attributes.realUser(), handle.requestStartedAt(), request.upstreamTraceId());
        return handle;
    }

    public long duration(Handle handle) {
        return Math.max(0, Duration.between(handle.requestStartedAt(), clock.instant()).toMillis());
    }

    public void complete(Handle handle, ExecutionResult result, String selectedAgent, boolean staticReply) {
        repository.saveRouting(handle.id(), result.agentType() == null ? selectedAgent : result.agentType(), result.routerLlmCalls());
        Double cost;
        if (staticReply) {
            cost = 0.0;
        } else if (result.llmUsages() == null) {
            cost = result.promptTokens() / 1000.0 * models.getNormal().getPriceInputPer1k()
                    + result.completionTokens() / 1000.0 * models.getNormal().getPriceOutputPer1k();
        } else {
            cost = LlmUsage.totalCost(result.llmUsages());
        }
        finish(handle, result, result.promptTokens(), result.completionTokens(), cost);
    }

    public void fail(Handle handle, Throwable failure, String agent) {
        TurnCounters counters = failure instanceof ExecutionFailure execution ? execution.counters : null;
        Throwable cause = failure instanceof ExecutionFailure ? failure.getCause() : failure;
        var usages = counters == null ? null : counters.llmUsages();
        repository.saveRouting(handle.id(), agent, counters == null ? 0 : counters.routerLlmCalls());
        repository.finishTrace(handle.id(), "FAILED", null, 0, counters == null ? 0 : counters.toolCallCount(),
                usages == null ? null : LlmUsage.knownPromptTokens(usages),
                usages == null ? null : LlmUsage.knownCompletionTokens(usages),
                usages == null ? null : LlmUsage.totalCost(usages), duration(handle), cause.getMessage(),
                counters == null ? null : counters.reusedFactCount(),
                counters == null ? null : counters.plannerLlmCalls(), counters == null ? null : counters.answerLlmCalls(),
                counters == null ? null : counters.metricFetchCount(), counters == null ? null : counters.calculationCount(),
                counters == null ? null : counters.httpRequestCount());
    }

    /** 已完成业务幂等提交后，再用完整事件核对模型用量；无法核实跨片段费用时保留 null。 */
    public void completeRecovered(String traceId, Instant requestTime, ExecutionResult result) {
        var usage = repository.modelUsage(traceId);
        int routers = ((Number) usage.get("router_calls")).intValue();
        repository.saveRouting(traceId, result.agentType(), routers);
        finish(new Handle(traceId, requestTime), result, ((Number) usage.get("prompt_tokens")).intValue(),
                ((Number) usage.get("completion_tokens")).intValue(), null);
    }

    private void finish(Handle handle, ExecutionResult result, int promptTokens, int completionTokens, Double cost) {
        String status = "WAITING".equals(result.turnStatus()) ? "WAITING" : "FAILED".equals(result.turnStatus()) ? "FAILED" : "SUCCESS";
        repository.finishTrace(handle.id(), status, result.answer(), result.steps(), result.toolCallCount(),
                promptTokens, completionTokens, cost, duration(handle), null, result.reusedFactCount(),
                result.plannerLlmCalls(), result.answerLlmCalls(), result.metricFetchCount(),
                result.calculationCount(), result.httpRequestCount());
    }
}
