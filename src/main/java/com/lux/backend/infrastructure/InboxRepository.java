package com.lux.backend.infrastructure;

import com.lux.backend.common.Timestamps;
import com.lux.backend.common.JsonCodec;
import jakarta.persistence.EntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Inbox 持久化边界，处理接收去重、并发领取、状态转换和指数退避。
 */
@Repository
public class InboxRepository {
    /**
     * Inbox 实体访问接口，负责首次插入、协调和到期重试扫描。
     */
    private final InboxJpaRepository events;
    /**
     * JPA 持久化上下文入口，管理实体加载、flush 同步和 refresh 重读。
     */
    private final EntityManager entities;
    /**
     * 统一 JSON 编解码器，负责载荷构建、解析与规范化摘要。
     */
    private final JsonCodec json;

    /**
     * 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。
     */
    public InboxRepository(
        InboxJpaRepository events,
        EntityManager entities,
        JsonCodec json
    ) {
        this.events = events;
        this.entities = entities;
        this.json = json;
    }

    /**
     * 首次事件落库；相同摘要视为重复接收，不同摘要保留冲突信封并拒绝覆盖原内容。
     */
    @Transactional
    public boolean receive(
        String source,
        String id,
        String owner,
        JsonNode envelope
    ) {
        String hash = json.hash(envelope);
        events.insertIfAbsent(source, id, owner, hash, json.write(envelope));
        InboxEntity existing = events.findById(new InboxEntity.Id(source, id)).orElseThrow();
        entities.refresh(existing);
        if (existing.eventHash.equals(hash)) {return true;}
        // 保留首次事件及其状态，只追加冲突证据；相同身份的坏重投递不能抹掉已成功处理的事实。
        existing.conflictEnvelope = json.write(envelope);
        existing.errorMessage = "Conflicting event ID reused with different content";
        entities.flush();
        return false;
    }

    /**
     * 使用诊断 ID 留存已拒绝的入站记录，避免污染合法事件身份。
     */
    @Transactional
    public void receiveRejected(
        String source,
        String diagnosticId,
        String owner,
        JsonNode envelope,
        String error
    ) {
        events.insertRejectedIfAbsent(
            source,
            diagnosticId,
            owner,
            json.hash(envelope),
            json.write(envelope),
            shortError(error)
        );
    }

    /**
     * 加载事件并对 RECEIVED 状态做版本协调，返回本次处理所需快照。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Entry forProcessing(
        String source,
        String id
    ) {
        InboxEntity event = events.findById(new InboxEntity.Id(source, id)).orElseThrow();
        if (event.status.equals("RECEIVED")) {
            if (events.coordinate(source, id, event.version) != 1) {
                throw new ObjectOptimisticLockingFailureException(InboxEntity.class, new InboxEntity.Id(source, id));
            }
            entities.refresh(event);
        }
        return new Entry(event.source, event.eventId, json.read(event.envelope), event.status, event.attempts);
    }

    /**
     * 读取已到重试时间的 RECEIVED 记录定位键。
     */
    @Transactional(readOnly = true)
    public List<Key> pending() {
        return events
            .findFirst100ByStatusAndNextAttemptAtLessThanEqualOrderByReceivedAtAsc("RECEIVED", Timestamps.now())
            .stream()
            .map(event -> new Key(event.source, event.eventId))
            .toList();
    }

    /**
     * 在业务事务内将尚待处理的事件标为 APPLIED。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applied(
        String source,
        String id
    ) {
        InboxEntity event = events.findById(new InboxEntity.Id(source, id)).orElseThrow();
        if (!event.status.equals("RECEIVED")) {return;}
        event.status = "APPLIED";
        event.processedAt = Timestamps.now();
        entities.flush();
    }

    /**
     * 对仍为 RECEIVED 的记录保存永久拒绝原因和处理时间。
     */
    @Transactional
    public void rejected(
        String source,
        String id,
        String error
    ) {
        InboxEntity event = events.findById(new InboxEntity.Id(source, id)).orElseThrow();
        if (!event.status.equals("RECEIVED")) {return;}
        event.status = "REJECTED";
        event.errorMessage = shortError(error);
        event.processedAt = Timestamps.now();
        entities.flush();
    }

    /**
     * 保留 RECEIVED 状态并增加尝试次数，按指数退避安排下一次处理。
     */
    @Transactional
    public void retry(
        String source,
        String id,
        String error
    ) {
        InboxEntity event = events.findById(new InboxEntity.Id(source, id)).orElseThrow();
        if (!event.status.equals("RECEIVED")) {return;}
        event.attempts++;
        event.errorMessage = shortError(error);
        event.nextAttemptAt = Timestamps.now().plusSeconds(Math.min(60, 1 << Math.min(event.attempts, 6)));
        entities.flush();
    }

    /**
     * 把诊断信息截断到数据库错误字段允许的 1000 个字符。
     */
    private String shortError(String error) {return error.substring(0, Math.min(error.length(), 1000));}

    /**
     * 待处理 Inbox 记录的定位键，扫描队列时只携带来源和事件 ID。
     *
     * @param source 事件来源主题，与事件 ID 一起构成 Inbox 的去重身份。
     * @param id     入站事件 ID，与 source 一起定位 Inbox 记录。
     */
    public record Key(String source, String id) {}

    /**
     * 已加载并完成处理协调的 Inbox 快照，供协议校验和业务分派使用。
     *
     * @param source   事件来源主题，与事件 ID 一起构成 Inbox 的去重身份。
     * @param id       入站事件 ID，与 source 一起定位 Inbox 记录。
     * @param envelope 完整通信信封，包含资源身份、事件类型、时间和业务载荷。
     * @param status   当前 Inbox 处理状态；只有 RECEIVED 才进入业务应用。
     * @param attempts 已记录的失败次数，影响后续重试间隔。
     */
    public record Entry(String source, String id, JsonNode envelope, String status, int attempts) {}
}
