package com.lux.backend.task;

import com.lux.backend.common.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Session 独立拥有的生成任务；供应商回执由 connector 保存，不引用 Run/Turn/Item。 */
@Entity
@Table(name = "task")
public class TaskEntity {
    /** 业务任务 UUID，与原调用的 operationId 分开。 */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    String id;
    /** 用户和会话是唯一业务归属。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    String userId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", length = 36, nullable = false)
    String sessionId;
    /** 计划生成任务直接绑定同 owner/Session 的计划；历史直接任务为空。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plan_id", length = 36)
    String planId;
    /** 历史任务使用调用 UUID，计划子任务使用父回执保存的独立执行 UUID。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "operation_id", length = 36, nullable = false)
    String operationId;
    /** IMAGE/VIDEO 类别及 connector 拥有的原始参数。 */
    @Column(name = "file_type", length = 16, nullable = false)
    String fileType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "json")
    String payload;
    /** Task 六状态，确认的终态不可反转。 */
    @Column(length = 24, nullable = false)
    String status;
    /** ORM 版本约束短事务写入，与 connector 序号独立。 */
    @Version
    @Column(nullable = false)
    Long version = 1L;
    @Column(name = "provider_event_seq", nullable = false)
    long providerEventSeq;
    /** 固定有序来源清单用于恢复同一批文件，不是最终 Asset 列表。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_sources", columnDefinition = "json")
    String resultSources;
    /** GENERATION/IMPORT 阶段、分类和原消息；细分 provider 诊断保留在 Inbox。 */
    @Column(name = "error_stage", length = 16)
    String errorStage;
    @Column(name = "error_code", length = 64)
    String errorCode;
    @Column(name = "error_message", columnDefinition = "text")
    String errorMessage;
    /** 首次终态时间。 */
    @Column(name = "finished_at", columnDefinition = "datetime(6)")
    Instant finishedAt;
    /** Java 本地记录时间，不代表 Agent 执行顺序。 */
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    Instant createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    Instant updatedAt;
    protected TaskEntity() { }
    @PreUpdate
    void updated() { updatedAt = Timestamps.now(); }
}
