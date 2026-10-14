package com.lux.backend.chat;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Session 时间线条目；输入永久不绑定执行，Agent 条目要求真实 Run/Turn 来源。 */
@Entity(name = "ConversationItem")
@Table(name = "item")
public class ItemEntity {
    /** 稳定实体身份。 */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    String id;

    /** 所有者；每次查询和引用都限定该身份。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36)
    String userId;

    /** 所属 Session，输入和输出均归属于会话。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", length = 36)
    String sessionId;

    /** 不可变的 Java 首次登记位置。 */
    @Column(name = "sequence_no")
    long sequenceNo;

    /** USER/AGENT/SYSTEM 来源。 */
    @Column(name = "source", length = 16)
    String source;

    /** 具名内容分类；Agent 骨架可以未知。 */
    @Column(name = "kind", length = 32)
    String kind;

    /** 输出来源 Run；输入为空。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "run_id", length = 36)
    String runId;

    /** 输出来源 Turn；输入为空。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "turn_id", length = 36)
    String turnId;

    /** Python Turn 内索引；输入为空。 */
    @Column(name = "item_index")
    Long itemIndex;

    /** 完整来源版本；输入和骨架为零。 */
    @Column(name = "source_revision")
    long sourceRevision;

    /** 连续 Session 输入位置；输出为空。 */
    @Column(name = "input_sequence_no")
    Long inputSequenceNo;

    /** PARTIAL/COMPLETED/INTERRUPTED；骨架为空。 */
    @Column(name = "status", length = 16)
    String status;

    /** 工具调用和结果共享的原始区分大小写身份。 */
    @Column(name = "call_id", length = 255)
    String callId;

    /** 仅实际工具调用存工具名。 */
    @Column(name = "tool_name", length = 255)
    String toolName;

    /** 仅终态 TASK_EVENT 存任务身份。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "task_id", length = 36)
    String taskId;

    /** 显式内容变体的持久化表示。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "json")
    String payload;

    /** 完整内容完成时间；临时内容为空。 */
    @Column(name = "completed_at", columnDefinition = "datetime(6)")
    Instant completedAt;

    /** 本地 ORM 版本，不是 Python 来源版本。 */
    @Version
    @Column(name = "version")
    Long version = 1L;

    /** Java 首次登记时间。 */
    @Column(name = "created_at", columnDefinition = "datetime(6)")
    Instant createdAt;

    /** Java 最近投影更新时间。 */
    @Column(name = "updated_at", columnDefinition = "datetime(6)")
    Instant updatedAt;

    /** 本地变化刷新展示元数据，不改变来源版本。 */
    @PreUpdate void updated() { updatedAt = com.lux.backend.common.Timestamps.now(); }
    protected ItemEntity() {}
}
