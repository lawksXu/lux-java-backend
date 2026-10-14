package com.lux.backend.infrastructure;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import com.lux.backend.common.Timestamps;
import com.lux.backend.common.Ids;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.common.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Outbox 持久化边界，负责构造命令信封、保存待发记录及记录发送结果。 */
@Repository
public class OutboxRepository {
    /** Outbox 实体查询接口，维护同一主题和键的发送先后关系。 */
    private final OutboxJpaRepository events;
    /** JPA 持久化上下文入口，管理实体加载、flush 同步和 refresh 重读。 */
    private final EntityManager entities;
    /** 统一 JSON 编解码器，负责载荷构建、解析与规范化摘要。 */
    private final JsonCodec json;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public OutboxRepository(
        OutboxJpaRepository events,
        EntityManager entities,
        JsonCodec json
    ) {
        this.events = events;
        this.entities = entities;
        this.json = json;
    }

    /** 将调用者已固定身份的具名 v2 信封加入当前业务事务，不重新生成输入事件 ID。 */
    @Transactional
    public void enqueueEnvelope(String topic, String key, CommandPayload.Envelope envelope) {
        if (envelope.schemaVersion() != 2) {
            throw new IllegalArgumentException("Unsupported outbound protocol version");
        }
        Ids.require(envelope.eventId());
        Ids.require(envelope.userId());
        Ids.require(envelope.sessionId());
        Instant.parse(envelope.occurredAt());
        if (envelope instanceof CommandPayload.InputAccepted input
                && !input.eventId().equals(input.item().id())) {
            throw new IllegalArgumentException("Input event identity must equal Item identity");
        }
        Optional<OutboxEntity> original = events.findByEventId(envelope.eventId());
        String encoded = json.write(envelope);
        // 在调用者的业务事务提交前拒绝不可投递的内容，不能先返回 accepted 再永久堵住分区队头。
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MessagingLimits.MAX_ENVELOPE_BYTES) {
            throw ApiException.badRequest("Outbound envelope exceeds the v2 UTF-8 transport budget");
        }
        if (original.isPresent()) {
            OutboxEntity stored = original.get();
            if (!stored.userId.equals(envelope.userId()) || !stored.sessionId.equals(envelope.sessionId())
                    || !stored.topic.equals(topic) || !stored.partitionKey.equals(key)
                    || !json.read(stored.envelope).equals(json.read(encoded))) {
                throw ApiException.conflict("Conflicting outbound event identity");
            }
            return;
        }
        OutboxEntity event = new OutboxEntity();
        event.eventId = envelope.eventId();
        event.userId = envelope.userId();
        event.sessionId = envelope.sessionId();
        event.topic = topic;
        event.partitionKey = key;
        event.envelope = encoded;
        event.nextAttemptAt = Timestamps.now();
        event.createdAt = event.nextAttemptAt;
        event.version = 1L;
        entities.persist(event);
    }

    /** 按输入请求顺序返回已保存的原信封，保留时间字符串和输入的传输身份。 */
    @Transactional(readOnly = true)
    public List<CommandPayload.InputAccepted> originalInputEnvelopes(
            String owner, String session, List<String> itemIds) {
        if (itemIds.isEmpty()) return List.of();
        Map<String, CommandPayload.InputAccepted> byIdentity = new LinkedHashMap<>();
        for (OutboxEntity event : events.findByUserIdAndSessionIdAndEventIdInOrderByIdAsc(owner, session, itemIds)) {
            if (!Topics.AGENT_COMMANDS.equals(event.topic)) {
                throw ApiException.conflict("Input has an incompatible delivery topic");
            }
            CommandPayload.InputAccepted input = EventProtocol.input(json.read(event.envelope));
            if (!event.eventId.equals(input.eventId()) || !owner.equals(input.userId())
                    || !session.equals(input.sessionId())) {
                throw ApiException.conflict("Original input envelope ownership differs");
            }
            byIdentity.put(input.eventId(), input);
        }
        return itemIds.stream().map(id -> {
            CommandPayload.InputAccepted input = byIdentity.get(id);
            if (input == null) throw ApiException.conflict("Original input event unavailable: " + id);
            return input;
        }).toList();
    }

    /** 取最多 100 条符合顺序约束的到期命令，转换为网络发送所需快照。 */
    @Transactional(readOnly = true)
    public List<Pending> pending() {
        return events.pending(Timestamps.now(), PageRequest.of(0, 100)).stream()
                     .map(event -> new Pending(
                         event.id,
                         event.topic,
                         event.partitionKey,
                         event.envelope,
                         event.attempts
                     )).toList();
    }

    /** 发送确认后记录送达时间并清除错误，重复标记不会改写已送达记录。 */
    @Transactional
    public void delivered(long id) {
        OutboxEntity event = events.findById(id).orElseThrow();
        if (event.deliveredAt != null) {return;}
        event.deliveredAt = Timestamps.now();
        event.lastError = null;
        entities.flush();
    }

    /** 增加失败次数并保存下次发送时间，重试继续使用原事件 ID 和载荷。 */
    @Transactional
    public void failed(
        Pending event,
        String error
    ) {
        OutboxEntity stored = events.findById(event.id()).orElseThrow();
        if (stored.deliveredAt != null) {return;}
        int seconds = Math.min(300, 1 << Math.min(event.attempts(), 8));
        stored.attempts++;
        stored.lastError = error.substring(0, Math.min(error.length(), 1000));
        // 失败只移动下次发送时间，不重建事件；同主题/键后续记录仍等待本条送达。
        stored.nextAttemptAt = Timestamps.now().plusSeconds(seconds);
        entities.flush();
    }

    /**
     * 发送所需的 Outbox 快照，防止 Kafka 网络等待期间持有受管实体。
     * @param id Outbox 数据库自增 ID，用于写回本条命令的发送结果。
     * @param topic 要发送到的 Kafka 主题。
     * @param partitionKey Kafka 消息键；同一主题和键的命令保持发送顺序。
     * @param envelope 完整通信信封，包含资源身份、事件类型、时间和业务载荷。
     * @param attempts 已记录的失败次数，影响后续重试间隔。
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Pending(long id, String topic, String partitionKey, String envelope, int attempts) {}
}
