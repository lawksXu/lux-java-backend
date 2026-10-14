package com.lux.backend.infrastructure;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 发布器：在数据库业务事务之外发送 Kafka，成功后另行记录送达时间。
 * 发送成功但回执落库失败时可能重发，因此消费方仍须按事件 ID 幂等处理。
 */
@Component
public class OutboxPublisher {
    /** 可靠发送仓储，使待发命令可与业务数据一起提交。 */
    private final OutboxRepository outbox;
    /** Kafka 字符串消息发送客户端；发送确认不等于消费者已完成业务处理。 */
    private final KafkaTemplate<String,String> kafka;
    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public OutboxPublisher(OutboxRepository outbox,KafkaTemplate<String,String> kafka) { this.outbox=outbox; this.kafka=kafka; }
    /** 串行发送本次待发快照，等待 Kafka 确认后标记送达；失败保留原事件身份并退避。 */
    public synchronized void publishPending() {
        for (OutboxRepository.Pending event:outbox.pending()) {
            try {
                // 等待 broker 确认后再写送达标记；两步之间若中断，后续会重复发送同一 event_id。
                kafka.send(event.topic(),event.partitionKey(),event.envelope()).get(15,TimeUnit.SECONDS);
                outbox.delivered(event.id());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception failure) {
                outbox.failed(event,failure.getClass().getSimpleName()+": "+failure.getMessage());
            }
        }
    }
}
