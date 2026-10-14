package com.lux.backend.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用 Spring 定时任务扫描，使 BackgroundWorkers 上的 @Scheduled 方法生效。 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration { }
