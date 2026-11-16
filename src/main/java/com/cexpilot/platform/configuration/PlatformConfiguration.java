package com.cexpilot.platform.configuration;

import com.cexpilot.platform.context.evidence.ToolEvidenceWriter;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 公共库的显式装配入口；宿主通过 @Import 引入，不需要扫描 com.cexpilot 根包。
 * 宿主负责提供 Agent、DataSource、LlmClient 与 ToolEvidenceWriter；底座默认提供 Clock 和数据库 TraceSink，可由宿主替换。
 * 本配置不加载业务 application.yml，也不自动执行数据库脚本。
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(value = "com.cexpilot.platform", excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = AutoConfiguration.class))
@EnableScheduling
public class PlatformConfiguration {}
