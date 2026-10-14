package com.lux.backend.chat;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** 一次模型调用及对应工具和后处理的事实投影，独立于父 Run 版本。 */
@Entity(name = "AgentTurn")
@Table(name = "agent_turn")
public class TurnEntity {
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

    /** 真实来源 Run。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "run_id", length = 36)
    String runId;

    /** Python 分配的 Run 内 Turn 索引。 */
    @Column(name = "turn_index")
    long turnIndex;

    /** 独立的完整来源版本。 */
    @Column(name = "source_revision")
    long sourceRevision;

    /** 来源生命周期；身份骨架为空。 */
    @Column(name = "status", length = 24)
    String status;

    /** 模型调用开始时间。 */
    @Column(name = "started_at", columnDefinition = "datetime(6)")
    Instant startedAt;

    /** 确认终态时间。 */
    @Column(name = "finished_at", columnDefinition = "datetime(6)")
    Instant finishedAt;

    /** 错误分类。 */
    @Column(name = "error_code", length = 64)
    String errorCode;

    /** 来源诊断。 */
    @Column(name = "error_message", columnDefinition = "text")
    String errorMessage;

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
    protected TurnEntity() {}
}
