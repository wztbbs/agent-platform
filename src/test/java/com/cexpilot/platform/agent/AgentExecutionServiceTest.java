package com.cexpilot.platform.agent;

import com.cexpilot.platform.context.session.*;
import com.cexpilot.platform.configuration.autoconfigure.TraceAutoConfiguration;
import com.cexpilot.platform.context.evidence.EvidenceSession;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.llm.*;
import com.cexpilot.platform.observability.*;
import com.cexpilot.platform.run.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentExecutionServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant start = Instant.parse("2026-11-16T03:00:00.123456Z");
    private final Clock clock = Clock.fixed(start.plusSeconds(3), ZoneOffset.UTC);
    private TraceRepository repository;
    private TraceSink sink;
    private TraceService traces;
    private JdbcTemplate jdbc;

    @BeforeEach void database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:entry_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("platform/db/schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        repository = new TraceRepository(jdbc, new TraceDatabaseTime("Asia/Shanghai"));
        sink = new DbTraceSink(repository);
        traces = new TraceService(repository, new LlmConfig(), clock, (String) null);
    }

    private AgentExecutionService service(Function<AgentDispatchRequest, ExecutionResult> run, AgentSessionProvider sessions) {
        return new AgentExecutionService(run, () -> "prompts-v2", sessions, traces, sink, clock);
    }
    private ExecutionResult result(String status) {
        return new ExecutionResult("完成", json.createArrayNode(), 2, 1, 10, 5, status, null)
                .withAgentRouteAndPayload("demo", null).withLlmUsages(List.of(new LlmUsage("normal", "script", 10, 5, 0.12)));
    }

    @Test void platformAssemblesSessionTimeTraceAndUnifiedEvents() {
        AgentInput businessContext = new AgentInput() {};
        var session = new EvidenceSession("conversation", "turn");
        var requestContext = new AtomicReference<RequestContext>();
        var provider = new AgentSessionProvider() {
            public TraceAttributes traceAttributes(AgentExecutionRequest request) {
                return new TraceAttributes(false, false, null, "turn");
            }
            public AgentExecutionSession resolve(AgentExecutionRequest request, String traceId, RequestContext context) {
                requestContext.set(context);
                return new AgentExecutionSession("history from port", businessContext, session, json.createObjectNode().put("previous", "demo"), null);
            }
        };
        var events = new ArrayList<AgentExecutionEvent>();
        var response = service(request -> {
            assertEquals("history from port", request.history());
            assertSame(businessContext, request.input());
            assertSame(session, request.session());
            assertEquals("demo", request.routingState().path("previous").asText());
            assertSame(requestContext.get(), request.requestContext());
            assertEquals(start, request.requestContext().requestTime());
            assertEquals("visitor", request.visitor());
            request.selectedAgent().accept("demo");
            request.progress().accept("执行中");
            request.delta().accept("完成");
            return result("SUCCEEDED");
        }, provider).execute(new AgentExecutionRequest("执行", "conversation", "visitor", ZoneId.of("Asia/Shanghai"), start, null, "upstream"), events::add);
        assertEquals(List.of(AgentExecutionEvent.Type.STARTED, AgentExecutionEvent.Type.AGENT_SELECTED,
                AgentExecutionEvent.Type.PROGRESS, AgentExecutionEvent.Type.DELTA), events.stream().map(AgentExecutionEvent::type).toList());
        assertTrue(events.stream().allMatch(event -> event.traceId().equals(response.traceId())));
        assertEquals("turn", response.turnId());
        assertEquals(3000, response.durationMs());
        var row = repository.findTrace(response.traceId());
        assertEquals("SUCCESS", row.get("status"));
        assertEquals("upstream", row.get("upstream_trace_id"));
        assertEquals(start.toString(), row.get("request_started_at"));
        assertEquals("prompts-v2", row.get("prompt_version"));
        assertEquals(false, row.get("is_real_user"));
        assertEquals(0.12, ((Number) row.get("cost")).doubleValue(), 1e-12);
    }

    @Test void simpleRequestUsesPlatformDefaultsAndNeverReusesUpstreamId() {
        var engine = service(request -> {
            assertNull(request.delta(), "未订阅增量时保持普通同步模型调用");
            assertNull(request.progress());
            return result("SUCCEEDED");
        }, new BasicAgentSessionProvider());
        var first = engine.execute(new AgentExecutionRequest("执行", null, "visitor", null, null, null, "parent"));
        var second = engine.execute(new AgentExecutionRequest("执行", first.conversationId(), "visitor", null, null, null, "parent"));
        assertNotEquals(first.traceId(), second.traceId());
        assertNotEquals("parent", first.traceId());
        assertNotNull(first.turnId());
        assertEquals(clock.instant(), first.requestStartedAt());
        assertEquals(first.conversationId(), second.conversationId());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ask_trace", Integer.class));
    }

    @Test void preparationFailureAndMetaFailureFinishTraceAndPreserveOriginalError() {
        var original = new IllegalStateException("session unavailable");
        var broken = new AgentSessionProvider() {
            public AgentExecutionSession resolve(AgentExecutionRequest request, String traceId, RequestContext context) { throw original; }
        };
        assertSame(original, assertThrows(IllegalStateException.class, () -> service(request -> fail("must not dispatch"), broken)
                .execute(new AgentExecutionRequest("执行", "c", "v", null, start))));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM ask_trace", String.class));
        assertEquals("session unavailable", jdbc.queryForObject("SELECT error FROM ask_trace", String.class));
        var disconnected = new IllegalStateException("disconnected");
        assertSame(disconnected, assertThrows(IllegalStateException.class, () -> service(request -> fail("must not dispatch"), new BasicAgentSessionProvider())
                .execute(new AgentExecutionRequest("执行", "c", "v", null, start), event -> { throw disconnected; })));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ask_trace WHERE status='FAILED'", Integer.class));
    }

    @Test void executionFailureRetainsCountersUsageAndUnknownCost() {
        var counters = new TurnCounters(2, 0, 1, 0, 3, 0, List.of()).withToolCalls(1)
                .withLlmUsages(List.of(new LlmUsage("normal", "one", 10, 5, 0.1), new LlmUsage("flagship", "two", null, null, null)));
        var failure = new ExecutionFailure(new IllegalStateException("timeout"), counters);
        assertSame(failure, assertThrows(ExecutionFailure.class, () -> service(request -> { throw failure; }, new BasicAgentSessionProvider())
                .execute(new AgentExecutionRequest("执行", "c", "v", null, start))));
        var row = jdbc.queryForMap("SELECT * FROM ask_trace");
        assertEquals(10, row.get("prompt_tokens"));
        assertEquals(1, row.get("tool_calls"));
        assertEquals(2, row.get("planner_llm_calls"));
        assertNull(row.get("cost"));
    }

    @Test void traceCleanupFailureIsSuppressedRatherThanReplacingExecutionFailure() {
        var broken = mock(TraceRepository.class);
        doThrow(new IllegalStateException("trace unavailable")).when(broken).saveRouting(any(), any(), anyInt());
        var engine = new AgentExecutionService(request -> { throw new IllegalArgumentException("business"); }, () -> "v",
                new BasicAgentSessionProvider(), new TraceService(broken, new LlmConfig(), clock, (String) null), sink, clock);
        var failure = assertThrows(IllegalArgumentException.class, () -> engine.execute(new AgentExecutionRequest("执行", "c", "v", null, start)));
        assertEquals("business", failure.getMessage());
        assertEquals("trace unavailable", failure.getSuppressed()[0].getMessage());
    }

    @Test void waitingTraceFinishesAfterRecoveryAndRepeatedPublicationCannotOverwriteTerminalResult() {
        var response = service(request -> {
            request.sink().record(TraceEvent.llmCall(request.traceId(), "router", null, null, 1L, 3, 2, null, null, null));
            request.sink().record(TraceEvent.llmCall(request.traceId(), "plan", null, null, 1L, 10, 5, null, null, null));
            return result("WAITING");
        }, new BasicAgentSessionProvider()).execute(new AgentExecutionRequest("执行", "c", "v", null, start));
        assertEquals("WAITING", repository.findTrace(response.traceId()).get("status"));
        assertNull(repository.findTrace(response.traceId()).get("finished_at"));
        // 新建服务实例模拟新进程，沿用原 Trace 和原始请求时间。
        var recovered = new TraceService(repository, new LlmConfig(), Clock.fixed(start.plusSeconds(3600), ZoneOffset.UTC), (String) null);
        recovered.completeRecovered(response.traceId(), start, result("SUCCEEDED"));
        var row = repository.findTrace(response.traceId());
        assertEquals("SUCCESS", row.get("status"));
        assertEquals(13, row.get("prompt_tokens"));
        assertEquals(7, row.get("completion_tokens"));
        assertEquals(1, row.get("router_llm_calls"));
        assertEquals(3600000L, ((Number) row.get("duration_ms")).longValue());
        recovered.completeRecovered(response.traceId(), start, result("FAILED"));
        assertEquals("SUCCESS", repository.findTrace(response.traceId()).get("status"));
    }

    @Test void waitingAndLeaseLossSignalsDoNotBecomeFailedTraces() {
        var lost = new RunLeaseLostException("new owner");
        assertSame(lost, assertThrows(RunLeaseLostException.class, () -> service(request -> { throw lost; }, new BasicAgentSessionProvider())
                .execute(new AgentExecutionRequest("执行", "c", "v", null, start))));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM ask_trace", String.class));
    }

    @Test void upgradePreservesHistoricalRowsAndUsesUtcForNewRequestTime() {
        jdbc.execute("ALTER TABLE ask_trace DROP COLUMN request_started_at");
        jdbc.execute("ALTER TABLE ask_trace DROP COLUMN upstream_trace_id");
        jdbc.update("INSERT INTO ask_trace(trace_id,question) VALUES ('historical','old question')");
        new ResourceDatabasePopulator(new ClassPathResource("platform/db/migrations/002_trace_request_context.sql")).execute(jdbc.getDataSource());
        assertEquals("old question", repository.findTrace("historical").get("question"));
        assertNull(repository.findTrace("historical").get("request_started_at"));
        var response = service(request -> result("SUCCEEDED"), new BasicAgentSessionProvider())
                .execute(new AgentExecutionRequest("执行", "c", "v", null, start));
        assertEquals(start.toString(), repository.findTrace(response.traceId()).get("request_started_at"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ask_trace", Integer.class));
    }

    @Test void autoConfigurationSuppliesDefaultSinkAndClockAndAllowsHostOverrides() {
        var runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class))
                .withBean(TraceRepository.class, () -> repository);
        runner.run(context -> {
            assertInstanceOf(DbTraceSink.class, context.getBean(TraceSink.class));
            assertNotNull(context.getBean(Clock.class));
        });
        TraceSink custom = event -> {};
        runner.withBean(TraceSink.class, () -> custom).withBean(Clock.class, () -> clock).run(context -> {
            assertSame(custom, context.getBean(TraceSink.class));
            assertSame(clock, context.getBean(Clock.class));
            assertEquals(1, context.getBeansOfType(TraceSink.class).size());
        });
    }
}
