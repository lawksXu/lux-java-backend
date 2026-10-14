package com.lux.backend.chat;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Session 只管理业务会话和首次登记序号，不保存执行槽位或输入消费水位。 */
@Entity(name = "ChatSession")
@Table(name = "`session`")
public class SessionEntity {
    /** 稳定实体身份。 */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36)
    String id;

    /** 所有者；每次查询和引用都限定该身份。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36)
    String userId;

    /** 展示标题。 */
    @Column(name = "title", length = 255)
    String title;

    /** 当前聚焦的业务计划；不代表 Run 或 Task 的调度槽位。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_plan_id", length = 36)
    String activePlanId;

    /** 仅聚焦指针变化时递增，普通消息和计划参数更新不影响此值。 */
    @Column(name = "plan_focus_revision", nullable = false)
    long planFocusRevision;

    /** 最后已分配的登记位置，分配前在 Session 锁内递增。 */
    @Column(name = "next_item_seq")
    long nextItemSeq;

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
    protected SessionEntity() {}
}
