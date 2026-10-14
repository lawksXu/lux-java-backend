package com.lux.backend.chat;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Session 级不可变执行日志；缺口只触发补齐，不阻止独立实体投影。 */
@Entity(name = "RunEventEntry")
@Table(name = "run_event")
public class RunEventEntity {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    String id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36)
    String userId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", length = 36)
    String sessionId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "run_id", length = 36)
    String runId;
    /** 单条 Item 事实的真实路由；完整多 Item 响应保持 null，不建立投影外键。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "item_id", length = 36)
    String itemId;
    /** 仅文本片段声明的位置，唯一键不允许另一事件 ID 复用它。 */
    @Column(name = "stream_seq")
    Long streamSeq;
    @Column(name = "sequence_no")
    long sequenceNo;
    @Column(name = "event_type", length = 64)
    String eventType;
    @Column(name = "occurred_at", columnDefinition = "datetime(6)")
    Instant occurredAt;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_hash", length = 64)
    String eventHash;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "json")
    String envelope;
    @Column(name = "created_at", columnDefinition = "datetime(6)")
    Instant createdAt;
    protected RunEventEntity() {
    }
}
