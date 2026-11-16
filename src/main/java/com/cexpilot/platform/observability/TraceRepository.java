package com.cexpilot.platform.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TraceRepository {

    private final JdbcTemplate jdbc;

    private final TraceDatabaseTime databaseTime;
    public TraceRepository(JdbcTemplate jdbc) { this(jdbc, new TraceDatabaseTime("UTC")); }
    @org.springframework.beans.factory.annotation.Autowired
    public TraceRepository(JdbcTemplate jdbc, TraceDatabaseTime databaseTime) {
        this.jdbc = jdbc;
        this.databaseTime = databaseTime;
    }

    public void saveRouting(String traceId,String agent,int routerCalls) {
        jdbc.update("UPDATE ask_trace SET agent_type=?,router_llm_calls=? WHERE trace_id=?",agent,routerCalls,traceId);
    }

    public void startTrace(String traceId, String conversationId, String question, String model,
                           String promptVersion, String visitorId, String buildCommit) {
        startTrace(traceId, conversationId, question, model, promptVersion, visitorId, buildCommit, true);
    }

    public void startTrace(String traceId, String conversationId, String question, String model,
                           String promptVersion, String visitorId, String buildCommit, boolean isRealUser) {
        startTrace(traceId, conversationId, question, model, promptVersion, visitorId, buildCommit,
                isRealUser, java.time.Instant.now(), null);
    }

    /** 请求时间按 UTC 存储，与数据库默认时区解耦；上游 ID 仅作关联，不代替本次 Trace ID。 */
    public void startTrace(String traceId, String conversationId, String question, String model,
                           String promptVersion, String visitorId, String buildCommit, boolean isRealUser,
                           java.time.Instant requestStartedAt, String upstreamTraceId) {
        jdbc.update("""
                INSERT INTO ask_trace (trace_id, conversation_id, question, status, model, prompt_version,
                    visitor_id, build_commit, is_real_user, request_started_at, upstream_trace_id)
                VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?)
                """, traceId, conversationId, question, model, promptVersion, visitorId, buildCommit, isRealUser,
                java.time.LocalDateTime.ofInstant(requestStartedAt, java.time.ZoneOffset.UTC), upstreamTraceId);
    }

    public void finishTrace(String traceId, String status, String answer, int llmSteps, int toolCalls,
                            Integer promptTokens, Integer completionTokens, Double cost,
                            long durationMs, String error) {
        finishTrace(traceId, status, answer, llmSteps, toolCalls, promptTokens, completionTokens, cost,
                durationMs, error, null);
    }

    /**
     * @param reusedFacts 本轮复用的历史事实数（阶段二）；null 表示不更新该列（失败路径等）
     */
    public void finishTrace(String traceId, String status, String answer, int llmSteps, int toolCalls,
                            Integer promptTokens, Integer completionTokens, Double cost,
                            long durationMs, String error, Integer reusedFacts) {
        finishTrace(traceId, status, answer, llmSteps, toolCalls, promptTokens, completionTokens, cost,
                durationMs, error, reusedFacts, null, null, null, null, null);
    }

    /**
     * 阶段三计数拆分：planner/answer LLM 调用数、metric 真实取数节点数、算子节点数、真实 HTTP 请求数；
     * 各计数为 null 表示不更新该列（失败路径等）。
     */
    public void finishTrace(String traceId, String status, String answer, int llmSteps, int toolCalls,
                            Integer promptTokens, Integer completionTokens, Double cost,
                            long durationMs, String error, Integer reusedFacts,
                            Integer plannerLlmCalls, Integer answerLlmCalls, Integer metricFetchCount,
                            Integer calculationCount, Integer httpRequestCount) {
        jdbc.update("""
                        UPDATE ask_trace
                        SET status = ?, answer = ?, llm_steps = ?, tool_calls = ?,
                            prompt_tokens = ?, completion_tokens = ?, cost = ?, duration_ms = ?,
                            error = ?, reused_facts = COALESCE(?, reused_facts),
                            planner_llm_calls = COALESCE(?, planner_llm_calls),
                            answer_llm_calls = COALESCE(?, answer_llm_calls),
                            metric_fetch_count = COALESCE(?, metric_fetch_count),
                            calculation_count = COALESCE(?, calculation_count),
                            http_request_count = COALESCE(?, http_request_count),
                            finished_at = CASE WHEN ? = 'WAITING' THEN NULL ELSE NOW() END
                        WHERE trace_id = ? AND status IN ('RUNNING','WAITING')
                        """,
                status, answer, llmSteps, toolCalls, promptTokens, completionTokens, cost,
                durationMs, error, reusedFacts,
                plannerLlmCalls, answerLlmCalls, metricFetchCount, calculationCount, httpRequestCount,
                status, traceId);
    }

    public void appendEvent(String traceId, TraceEvent event) {
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM trace_event WHERE trace_id = ?",
                Integer.class, traceId);
        jdbc.update("""
                        INSERT INTO trace_event
                            (trace_id, seq, event_type, name, input_json, output_json,
                             duration_ms, prompt_tokens, completion_tokens, ttft_ms, cached_tokens, error)
                        VALUES (?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?, ?, ?, ?)
                        """,
                traceId, seq, event.eventType(), event.name(),
                event.inputJson(), event.outputJson(),
                event.durationMs(), event.promptTokens(), event.completionTokens(),
                event.ttftMs(), event.cachedTokens(), event.error());
    }

    /** 恢复执行以实际落库的模型调用汇总全部片段（包括入口 Router），不重复累加旧 Trace。 */
    public Map<String, Object> modelUsage(String traceId) {
        return jdbc.queryForMap("""
                SELECT COALESCE(SUM(prompt_tokens),0) prompt_tokens,
                       COALESCE(SUM(completion_tokens),0) completion_tokens,
                       COALESCE(SUM(CASE WHEN name='router' THEN 1 ELSE 0 END),0) router_calls
                FROM trace_event WHERE trace_id=? AND event_type='LLM_CALL'
                """, traceId);
    }

    public Map<String, Object> findTrace(String traceId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM ask_trace WHERE trace_id = ?", databaseTime.rowMapper(), traceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findEvents(String traceId) {
        return jdbc.query(
                "SELECT * FROM trace_event WHERE trace_id = ? ORDER BY seq", databaseTime.rowMapper(), traceId);
    }

    // 窗口由数据库自己的 NOW() 计算：created_at 是 DB 时钟写的，比较双方必须同为 DB 时钟，
    // 否则应用服务器（如硅谷）与数据库（如 UTC+8）时区不一致时会捞空。
    public List<Map<String, Object>> findTracesBetween(long beginMinutesAgo, long endMinutesAgo) {
        return jdbc.query("""
                        SELECT * FROM ask_trace
                        WHERE created_at BETWEEN NOW() - INTERVAL ? MINUTE AND NOW() - INTERVAL ? MINUTE
                        ORDER BY created_at DESC
                        """,
                databaseTime.rowMapper(), beginMinutesAgo, endMinutesAgo);
    }

    public java.time.OffsetDateTime dbNow() {
        return jdbc.queryForObject("SELECT NOW()", (rs, n) -> databaseTime.offset(rs.getObject(1, java.time.LocalDateTime.class)));
    }

    public Map<String, List<Map<String, Object>>> findEventsByTraceIds(List<String> traceIds) {
        return groupByTraceId(
                "SELECT * FROM trace_event WHERE trace_id IN (%s) ORDER BY trace_id, seq", traceIds);
    }

    private Map<String, List<Map<String, Object>>> groupByTraceId(String sql, List<String> traceIds) {
        if (traceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(traceIds.size(), "?"));
        List<Map<String, Object>> rows = jdbc.query(String.format(sql, placeholders), databaseTime.rowMapper(), traceIds.toArray());
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent((String) row.get("trace_id"), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }
}
