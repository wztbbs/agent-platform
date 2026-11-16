package com.cexpilot.platform.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trace 落库实现。事件写入失败不阻断执行，主记录的生命周期由 TraceService 管理。
 */
public class DbTraceSink implements TraceSink {

    private static final Logger log = LoggerFactory.getLogger(DbTraceSink.class);

    private final TraceRepository repository;

    public DbTraceSink(TraceRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(TraceEvent event) {
        try {
            repository.appendEvent(event.traceId(), event);
        } catch (RuntimeException e) {
            log.warn("trace 事件落库失败 traceId={} type={}", event.traceId(), event.eventType(), e);
        }
    }
}
