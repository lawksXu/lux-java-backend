package com.lux.backend.chat;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Python Run 的事实投影；来源版本零表示只有真实身份的骨架。 */
@Entity(name = "AgentRun")
@Table(name = "agent_run")
public class RunEntity {
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

    /** Python 分配的 Session 内 Run 索引。 */
    @Column(name = "run_index")
    long runIndex;

    /** 该实体独立的完整来源版本。 */
    @Column(name = "source_revision")
    long sourceRevision;

    /** 四种来源状态；骨架为空。 */
    @Column(name = "status", length = 24)
    String status;

    /** Python 开始时间。 */
    @Column(name = "started_at", columnDefinition = "datetime(6)")
    Instant startedAt;

    /** Python 终态时间。 */
    @Column(name = "finished_at", columnDefinition = "datetime(6)")
    Instant finishedAt;

    /** Python 明确选择的最终 Item，不从接收顺序推导。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "final_item_id", length = 36)
    String finalItemId;

    /** 已确认错误分类。 */
    @Column(name = "error_code", length = 64)
    String errorCode;

    /** 来源诊断。 */
    @Column(name = "error_message", columnDefinition = "text")
    String errorMessage;

    /** 最后有效身份观察时间，不决定执行结束。 */
    @Column(name = "last_seen_at", columnDefinition = "datetime(6)")
    Instant lastSeenAt;

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
    protected RunEntity() {}
}
