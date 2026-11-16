package com.cexpilot.platform.configuration;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 公共库的显式装配入口；宿主通过 @Import 引入，不需要扫描 com.cexpilot 根包。
 * 宿主负责提供 Agent、DataSource、Clock、LlmClient、TraceSink 与 ToolEvidenceWriter。
 * 本配置不加载业务 application.yml，也不自动执行数据库脚本。
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan("com.cexpilot.platform")
@EnableScheduling
public class PlatformConfiguration {}
