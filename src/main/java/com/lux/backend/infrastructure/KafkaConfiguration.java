package com.lux.backend.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.apache.kafka.common.config.TopicConfig;

/** Kafka 基础装配，声明 v2 通信主题及 legacy 生成事件主题并配置尚未可靠落库记录的监听重试策略。 */
@Configuration
public class KafkaConfiguration {
    /** 监听处理抛错时每秒重试，避免尚未可靠保存的 Kafka 记录因次数耗尽而被跳过。 */
    @Bean
    DefaultErrorHandler retryUnstoredRecords() {
        return new DefaultErrorHandler(new FixedBackOff(1000L,FixedBackOff.UNLIMITED_ATTEMPTS));
    }
    /** 声明 Agent 与生成连接器的命令、事件主题；本地配置每个主题三个分区、一个副本。 */
    @Bean
    KafkaAdmin.NewTopics luxTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(Topics.AGENT_COMMANDS).partitions(3).replicas(1)
                        .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, Integer.toString(MessagingLimits.MAX_RECORD_BYTES)).build(),
                TopicBuilder.name(Topics.AGENT_EVENTS).partitions(3).replicas(1)
                        .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, Integer.toString(MessagingLimits.MAX_RECORD_BYTES)).build(),
                TopicBuilder.name(Topics.GENERATION_COMMANDS).partitions(3).replicas(1)
                        .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, Integer.toString(MessagingLimits.MAX_RECORD_BYTES)).build(),
                TopicBuilder.name(Topics.GENERATION_EVENTS).partitions(3).replicas(1)
                        .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, Integer.toString(MessagingLimits.MAX_RECORD_BYTES)).build(),
                TopicBuilder.name(Topics.LEGACY_GENERATION_EVENTS).partitions(3).replicas(1).build());
    }

    /** 单次 poll 只处理一条记录，整体导入期限与 Kafka poll 期限之间保留技术恢复余量。 */
    @Bean
    InitializingBean importPollBudget(
            @Value("${spring.kafka.consumer.properties.max.poll.interval.ms:900000}") long pollMillis,
            @Value("${lux.task.import-timeout-ms:240000}") long importMillis,
            @Value("${spring.kafka.consumer.max-poll-records:1}") int maxRecords) {
        return () -> {
            if (importMillis <= 0 || pollMillis <= importMillis || pollMillis - importMillis < 60000
                    || maxRecords != 1) {
                throw new IllegalArgumentException("Kafka poll budget must exceed the single-record import deadline by 60 seconds");
            }
        };
    }
}
