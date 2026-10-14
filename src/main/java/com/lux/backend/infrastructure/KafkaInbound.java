package com.lux.backend.infrastructure;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka 事件监听入口，将消息及分区位置交给 InboxProcessor 持久化处理。 */
@Component
public class KafkaInbound {
    /** 入站事件处理器，负责协议校验、业务应用和失败分类。 */
    private final InboxProcessor inbox;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public KafkaInbound(InboxProcessor inbox) {this.inbox = inbox;}

    /** 转交消息来源、分区键及 offset；持久化失败抛错后由 Kafka 监听容器重试。 */
    @KafkaListener(topics = Topics.AGENT_EVENTS, groupId = "lux-java-backend-agent-v2")
    public void receiveAgent(ConsumerRecord<String, String> record) {
        inbox.receive(record.topic(), record.key(), record.value(), record.partition(), record.offset());
    }

    /** 结果导入完成前不返回；RECORD ACK 仅推进当前分区已经完成的前缀。 */
    @KafkaListener(topics = {Topics.GENERATION_EVENTS, Topics.LEGACY_GENERATION_EVENTS},
                   groupId = "lux-java-backend-generation-v2")
    public void receiveGeneration(ConsumerRecord<String, String> record) {
        inbox.receive(record.topic(), record.key(), record.value(), record.partition(), record.offset());
    }
}
