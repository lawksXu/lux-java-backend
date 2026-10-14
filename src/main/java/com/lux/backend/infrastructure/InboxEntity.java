package com.lux.backend.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * inbox_event 表映射，保存接收原文、处理状态、冲突证据和重试时间。
 */
@Entity
@Table(name = "inbox_event")
@IdClass(InboxEntity.Id.class)
public class InboxEntity {
    /**
     * 事件来源主题，与事件 ID 一起构成 Inbox 的去重身份。
     */
    @jakarta.persistence.Id
    @Column(length = 128, nullable = false)
    String source;
    /**
     * 通信事件 UUID；重复投递时保持不变，供消费方去重。
     */
    @jakarta.persistence.Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", columnDefinition = "char(36)", nullable = false)
    String eventId;
    /**
     * 所属用户 ID，用于标识业务归属与限定资源访问范围。
     */
    @Column(name = "user_id", length = 128, nullable = false)
    String userId;
    /**
     * 原始事件信封的规范化摘要，用于识别相同事件 ID 下的内容冲突。
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_hash", columnDefinition = "char(64)", nullable = false)
    String eventHash;
    /**
     * 完整通信信封，包含资源身份、事件类型、时间和业务载荷。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    String envelope;
    /**
     * 同一事件 ID 后续收到的不同内容，保留用于诊断而不覆盖原事件。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conflict_envelope")
    String conflictEnvelope;
    /**
     * 接收处理状态：RECEIVED 待应用、APPLIED 已提交、REJECTED 永久拒绝。
     */
    @Column(length = 16, nullable = false)
    String status;
    /**
     * 最近记录的业务或处理错误；正常状态下可为空。
     */
    @Column(name = "error_message", length = 1000)
    String errorMessage;
    /**
     * 已记录的失败次数，影响后续重试间隔。
     */
    @Column(nullable = false)
    int attempts;
    /**
     * 下一次允许处理或发送的时间，用于持久化重试退避。
     */
    @Column(name = "next_attempt_at", nullable = false)
    Instant nextAttemptAt;
    /**
     * 首次接收入库时间，用于重试扫描排序。
     */
    @Column(name = "received_at", nullable = false, updatable = false)
    Instant receivedAt;
    /**
     * Inbox 应用成功或永久拒绝的时间，待重试时为空。
     */
    @Column(name = "processed_at")
    Instant processedAt;
    /**
     * Hibernate 乐观锁版本；更新时比较并递增，防止并发操作静默覆盖彼此。
     */
    @Version
    @Column(nullable = false)
    Long version;

    /**
     * 供 JPA 通过反射创建实体，字段由持久化映射填充。
     */
    public InboxEntity() {}

    /**
     * Inbox 复合主键：事件来源与事件 ID 共同定义去重身份。
     */
    public static class Id implements Serializable {
        /**
         * 事件来源主题，与事件 ID 一起构成 Inbox 的去重身份。
         */
        public String source;
        /**
         * 通信事件 UUID；重复投递时保持不变，供消费方去重。
         */
        public String eventId;

        /**
         * 供 JPA 反射创建复合主键实例。
         */
        public Id() {}

        /**
         * 用完整键字段创建可用于仓储查询的复合主键。
         */
        public Id(
            String source,
            String eventId
        ) {
            this.source = source;
            this.eventId = eventId;
        }

        /**
         * 按全部复合主键字段比较身份，保证 JPA 与集合中的相等性判断一致。
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof Id id && Objects.equals(source, id.source) && Objects.equals(eventId, id.eventId);
        }

        /**
         * 使用与 equals 相同的主键字段计算哈希，满足复合主键对象契约。
         */
        @Override
        public int hashCode() {return Objects.hash(source, eventId);}
    }
}
