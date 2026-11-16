package com.cexpilot.platform.configuration.autoconfigure;

import com.cexpilot.platform.observability.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

/** 组件装配完成后提供默认 Trace 出口；宿主可通过自己的 TraceSink 替换。 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@ConditionalOnBean(TraceRepository.class)
public class TraceAutoConfiguration {
    @Bean @ConditionalOnMissingBean(TraceSink.class)
    public DbTraceSink dbTraceSink(TraceRepository repository) { return new DbTraceSink(repository); }
    @Bean @ConditionalOnMissingBean(Clock.class)
    public Clock platformClock() { return Clock.systemUTC(); }
}
