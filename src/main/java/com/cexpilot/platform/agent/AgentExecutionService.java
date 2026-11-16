package com.cexpilot.platform.agent;

import com.cexpilot.platform.context.session.*;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.observability.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** 宿主的统一执行入口；公共请求无需携带 Trace、历史、证据会话或内部引擎回调。 */
@Service
public class AgentExecutionService {
    private static final Logger log = LoggerFactory.getLogger(AgentExecutionService.class);
    private final Function<AgentDispatchRequest, ExecutionResult> dispatch;
    private final Supplier<String> promptVersion;
    private final AgentSessionProvider sessions;
    private final TraceService traces;
    private final TraceSink sink;
    private final Clock clock;

    /** 本次同步执行的状态，保存路由回调选中的 Agent，供结果与失败收尾读取。 */
    private static final class ExecutionState {
        private String selectedAgent;
    }

    @Autowired
    public AgentExecutionService(AgentDispatcher dispatcher, ObjectProvider<AgentSessionProvider> sessions,
                                 TraceService traces, TraceSink sink, Clock clock) {
        this(dispatcher::execute, dispatcher::promptVersion, sessions.getIfAvailable(BasicAgentSessionProvider::new), traces, sink, clock);
    }

    public AgentExecutionService(Function<AgentDispatchRequest, ExecutionResult> dispatch, Supplier<String> promptVersion,
                                 AgentSessionProvider sessions, TraceService traces, TraceSink sink, Clock clock) {
        this.dispatch = dispatch;
        this.promptVersion = promptVersion;
        this.sessions = sessions;
        this.traces = traces;
        this.sink = sink;
        this.clock = clock;
    }

    public AgentExecutionResponse execute(AgentExecutionRequest request) {
        return execute(request, AgentExecutionListener.NONE);
    }

    public AgentExecutionResponse execute(AgentExecutionRequest input, AgentExecutionListener listener) {
        var request = normalize(input);
        var events = Objects.requireNonNullElse(listener, AgentExecutionListener.NONE);
        var executionState = new ExecutionState();
        TraceService.Handle trace = null;
        try {
            var attributes = sessions.traceAttributes(request);
            trace = traces.start(request, attributes, attributes.staticReply() ? null : promptVersion.get());
            return executeInTrace(request, events, attributes, trace, executionState);
        } catch (com.cexpilot.platform.run.RunSuspended | com.cexpilot.platform.run.RunLeaseLostException control) {
            // 等待和所有权丢失由持久化运行处理，不能把仍可恢复的 Trace 标成失败。
            throw control;
        } catch (RuntimeException | Error failure) {
            if (trace != null) {
                try {
                    traces.fail(trace, failure, executionState.selectedAgent);
                } catch (RuntimeException secondary) {
                    failure.addSuppressed(secondary);
                }
            }
            log.error("Agent 执行失败 conversationId={} traceId={}", request.conversationId(), trace == null ? null : trace.id(), failure);
            throw failure;
        }
    }

    private AgentExecutionResponse executeInTrace(AgentExecutionRequest request, AgentExecutionListener events,
                                                  TraceAttributes attributes, TraceService.Handle trace,
                                                  ExecutionState executionState) {
        emit(events, request, attributes.turnId(), trace.id(), AgentExecutionEvent.Type.STARTED, null);
        var context = new RequestContext(request.userZone(), request.requestStartedAt());
        var session = sessions.resolve(request, trace.id(), context);
        String turnId = session.evidenceSession() == null ? attributes.turnId() : session.evidenceSession().turnId();
        ExecutionResult result;
        if (session.preset() != null) {
            result = session.preset();
            executionState.selectedAgent = result.agentType();
            emit(events, request, turnId, trace.id(), AgentExecutionEvent.Type.AGENT_SELECTED, result.agentType());
            sink.record(new TraceEvent(trace.id(), "STATIC_REPLY", "preset_reply", null,
                    result.agentPayload() == null ? null : result.agentPayload().toString(), null, null, null, null, null, null));
        } else {
            var dispatchRequest = AgentDispatchRequest.builder(request.question(), trace.id(), sink)
                    .history(session.history()).input(session.preparedInput()).session(session.evidenceSession())
                    .routingState(session.routingState()).visitor(request.visitor()).requestContext(context)
                    .delta(events.accepts(AgentExecutionEvent.Type.DELTA)
                            ? text -> emit(events, request, turnId, trace.id(), AgentExecutionEvent.Type.DELTA, text) : null)
                    .progress(events.accepts(AgentExecutionEvent.Type.PROGRESS)
                            ? text -> emit(events, request, turnId, trace.id(), AgentExecutionEvent.Type.PROGRESS, text) : null)
                    .selectedAgent(agent -> {
                        executionState.selectedAgent = agent;
                        emit(events, request, turnId, trace.id(), AgentExecutionEvent.Type.AGENT_SELECTED, agent);
                    }).build();
            result = dispatch.apply(dispatchRequest);
        }
        traces.complete(trace, result, executionState.selectedAgent, attributes.staticReply());
        return new AgentExecutionResponse(result, request.conversationId(), turnId, trace.id(),
                request.requestStartedAt(), request.userZone(), traces.duration(trace));
    }

    private AgentExecutionRequest normalize(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString() : request.conversationId();
        return new AgentExecutionRequest(request.question(), conversationId, request.visitor(), request.userZone(),
                request.requestStartedAt() == null ? clock.instant() : request.requestStartedAt(), request.businessInput(), request.upstreamTraceId());
    }

    private void emit(AgentExecutionListener listener, AgentExecutionRequest request, String turnId, String traceId,
                      AgentExecutionEvent.Type type, String text) {
        if (listener.accepts(type)) {
            listener.onEvent(new AgentExecutionEvent(type, request.conversationId(), turnId, traceId, text));
        }
    }
}
