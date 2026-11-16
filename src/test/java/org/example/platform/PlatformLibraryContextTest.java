package org.example.platform;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.configuration.PlatformConfiguration;
import com.cexpilot.platform.context.evidence.ToolEvidenceWriter;
import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.llm.*;
import com.cexpilot.platform.observability.TraceSink;
import com.cexpilot.platform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/** 仅依赖公共 JAR 的非 CEX 宿主；脚本模型和 H2，不访问任何外部服务。 */
@SpringBootTest(classes = PlatformLibraryContextTest.HostApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:platform_library;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:platform/db/schema.sql",
        "spring.main.web-application-type=none"
})
class PlatformLibraryContextTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(PlatformConfiguration.class)
    static class HostApplication {
        @Bean ToolEvidenceWriter evidence() { return (conversation, turn, trace, agent, tool, data, provenance, now) -> Optional.empty(); }
        @Bean(LlmClient.NORMAL_LLM_CLIENT)
        LlmClient model() {
            var responses = new ConcurrentLinkedQueue<>(List.of(
                    "{\"route\":\"library_demo\"}",
                    "{\"nodes\":[{\"id\":\"n1\",\"tool\":\"avg\",\"args\":{\"input\":{\"kind\":\"values\",\"values\":[1,3]}}}]}",
                    "{\"answer\":\"平均值为2\",\"evidence_refs\":[\"n1\"]}"));
            return (messages, tools) -> new ChatResponse(responses.remove(), List.of(), 10, 5);
        }
    }

    @Autowired AgentExecutionService execution;
    @Autowired AgentRegistry agents;
    @Autowired ToolRegistry tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResourceLoader resources;

    @Test void nonCexHostRunsRegisteredPlanThroughSharedRuntime() {
        var response = execution.execute(new AgentExecutionRequest("求1和3的平均值", null, "library-user", ZoneOffset.UTC, null));
        var result = response.result();
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM ask_trace WHERE trace_id=?", String.class, response.traceId()));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM trace_event WHERE trace_id=? AND event_type='LLM_CALL'", Integer.class, response.traceId()));
        assertEquals("library_demo", result.agentType());
        assertEquals("平均值为2", result.answer());
        assertEquals(1, result.toolCallCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_run WHERE status='SUCCEEDED'", Integer.class));
        assertEquals(1, agents.definitions().size());
    }

    @Test void commonJarDoesNotBringCexConfigurationOrBusinessTables() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.cexpilot.business.cex.monitor.MonitorAgent"));
        assertFalse(resources.getResource("classpath:application.yml").exists());
        assertFalse(resources.getResource("classpath:prompts/monitor_planner.txt").exists());
        assertFalse(resources.getResource("classpath:demo/agents/plan.yml").exists());
        assertNull(tools.get("monitor.rule.create_draft"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='monitor_rule'", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN ('platform_run','platform_tool_call','platform_run_checkpoint','platform_prepared_plan')", Integer.class));
    }
}
