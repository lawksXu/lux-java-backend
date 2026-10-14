package com.lux.backend.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** outbox_event 表映射，保存与业务事务一起提交、随后待发送到 Kafka 的命令。 */
@Entity
@Table(name="outbox_event")
public class OutboxEntity {
    /** 数据库自增主键，用于确定 Outbox 发送顺序；不同于事件 UUID。 */
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    Long id;
    /** 通信事件 UUID；重复投递时保持不变，供消费方去重。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name="event_id",columnDefinition="char(36)",nullable=false,updatable=false)
    String eventId;
    /** 所属用户 ID，用于标识业务归属与限定资源访问范围。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name="user_id",columnDefinition="char(36)",nullable=false,updatable=false)
    String userId;
    /** 所属会话 ID，用于把消息、执行和通知归到同一个聊天会话。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name="session_id",columnDefinition="char(36)",nullable=false,updatable=false)
    String sessionId;
    /** 要发送到的 Kafka 主题。 */
    @Column(length=128,nullable=false,updatable=false)
    String topic;
    /** Kafka 消息键；同一主题和键的命令保持发送顺序。 */
    @Column(name="partition_key",length=128,nullable=false,updatable=false)
    String partitionKey;
    /** 完整通信信封，包含资源身份、事件类型、时间和业务载荷。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable=false,updatable=false)
    String envelope;
    /** 已记录的失败次数，影响后续重试间隔。 */
    @Column(nullable=false)
    int attempts;
    /** 下一次允许处理或发送的时间，用于持久化重试退避。 */
    @Column(name="next_attempt_at",nullable=false)
    Instant nextAttemptAt;
    /** Kafka 发送确认后保存的送达时间，空值表示仍需发送。 */
    @Column(name="delivered_at")
    Instant deliveredAt;
    /** 最近一次 Kafka 发布失败的诊断信息。 */
    @Column(name="last_error",length=1000)
    String lastError;
    /** 记录创建时间，持久化精度为微秒。 */
    @Column(name="created_at",nullable=false,updatable=false)
    Instant createdAt;
    /** Hibernate 乐观锁版本；更新时比较并递增，防止并发操作静默覆盖彼此。 */
    @Version
    @Column(nullable=false)
    Long version;

    /** 供 JPA 通过反射创建实体，字段由持久化映射填充。 */
    public OutboxEntity() { }
}
