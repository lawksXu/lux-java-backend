package com.lux.backend.chat;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.io.Serializable;
import java.util.Objects;

/** session_event 表映射，持久保存会话通知，使前端断线重连后仍可继续读取。 */
@Entity(name = "SessionEventEntry")
@Table(name = "session_event")
@IdClass(SessionEventEntity.Key.class)
public class SessionEventEntity {
    /** 所属会话 ID，用于把消息、执行和通知归到同一个聊天会话。 */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", length = 36)
    String sessionId;

    /** 会话内 SSE 事件序号，与消息序号和 Run 事件序号相互独立。 */
    @Id
    @Column(name = "sequence_no")
    long sequenceNo;

    /** 所属用户 ID，用于标识业务归属与限定资源访问范围。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36)
    String userId;

    /** 事件类型名称，决定消费方如何解释载荷。 */
    @Column(name = "event_type", length = 64)
    String eventType;

    /** 事件业务载荷；具体字段由 eventType 对应的协议约定。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "json")
    String payload;

    /** 记录创建时间，持久化精度为微秒。 */
    @Column(name = "created_at", columnDefinition = "datetime(6)")
    Instant createdAt;

    /** 供 JPA 通过反射创建实体，字段由持久化映射填充。 */
    protected SessionEventEntity() { }

    /** 会话事件复合主键：会话 ID 与会话内事件序号共同定位一条 SSE 通知。 */
    public static class Key implements Serializable {
        /** 所属会话 ID，用于把消息、执行和通知归到同一个聊天会话。 */
        public String sessionId;
        /** 会话内 SSE 事件序号，与消息序号和 Run 事件序号相互独立。 */
        public long sequenceNo;
        /** 供 JPA 反射创建复合主键实例。 */
        public Key() { }
        /** 用完整键字段创建可用于仓储查询的复合主键。 */
        public Key(String sessionId, long sequenceNo) {
            this.sessionId = sessionId;
            this.sequenceNo = sequenceNo;
        }
        /** 按全部复合主键字段比较身份，保证 JPA 与集合中的相等性判断一致。 */
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(sessionId, key.sessionId) && Objects.equals(sequenceNo, key.sequenceNo);
        }
        /** 使用与 equals 相同的主键字段计算哈希，满足复合主键对象契约。 */
        @Override public int hashCode() { return Objects.hash(sessionId, sequenceNo); }
    }
}
