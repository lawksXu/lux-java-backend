package com.lux.backend.generation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** 可复用计划的持久化配置；业务 revision 和 Hibernate version 分开管理。 */
@Entity
@Table(name = "generation_plan")
public class GenerationPlanEntity {
    /** 计划身份和直接 owner/Session 归属，不依赖执行记录。 */
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) String id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", length = 36, nullable = false) String userId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "session_id", length = 36, nullable = false) String sessionId;
    /** 业务 CAS 修订号，仅在实际配置改变或首次冻结时递增。 */
    @Column(nullable = false) long revision;
    /** 可编辑性，不保存 Task 的执行状态。 */
    @Column(length = 16, nullable = false) String state;
    /** 已知结构以具名 DTO 解码，JSON 列只承担持久化。 */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "json", nullable = false) String configuration;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "frozen_request", columnDefinition = "json") String frozenRequest;
    @Column(name = "frozen_at", columnDefinition = "datetime(6)") Instant frozenAt;
    /** ORM 并发版本不得替代业务 revision。 */
    @Version @Column(nullable = false) Long version = 1L;
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)") Instant createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)") Instant updatedAt;
    protected GenerationPlanEntity() { }
}
