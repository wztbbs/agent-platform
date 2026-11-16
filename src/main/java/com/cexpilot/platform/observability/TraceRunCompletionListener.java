package com.cexpilot.platform.observability;

import com.cexpilot.platform.execution.ExecutionResult;
import com.cexpilot.platform.run.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** 业务提交完成后才完成 Trace；不访问或解释宿主的会话、任务等业务表。 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class TraceRunCompletionListener implements RunCompletionListener {
    private final TraceService traces;
    public TraceRunCompletionListener(TraceService traces) { this.traces = traces; }
    public boolean completed(DurableStore.Entry run, ExecutionResult result) {
        traces.completeRecovered(run.data().path("traceId").asText(),
                Instant.parse(run.data().path("requestTime").asText()), result);
        return true;
    }
}
