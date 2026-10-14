package com.lux.backend.common;

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

/** http_request 表映射，保存幂等键、输入摘要与已完成操作的 JSON 响应回执。 */
@Entity
@Table(name="http_request")
@IdClass(HttpRequestEntity.Id.class)
public class HttpRequestEntity {
    /** 所属用户 ID，用于标识业务归属与限定资源访问范围。 */
    @jakarta.persistence.Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name="user_id",columnDefinition="char(36)",nullable=false)
    String userId;
    /** 当前用户范围内的 HTTP 幂等请求 UUID。 */
    @jakarta.persistence.Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name="request_id",columnDefinition="char(36)",nullable=false)
    String requestId;
    /** 原始请求的规范化 SHA-256 摘要，用于拒绝同一幂等身份携带不同输入。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name="request_hash",columnDefinition="char(64)",nullable=false,updatable=false)
    String requestHash;
    /** 已执行操作的 JSON 响应回执；SQL NULL 表示尚无结果，JSON null 也可表示已完成。 */
    @JdbcTypeCode(SqlTypes.JSON)
    String response;
    /** 记录创建时间，持久化精度为微秒。 */
    @Column(name="created_at",nullable=false,updatable=false)
    Instant createdAt;
    /** Hibernate 乐观锁版本；更新时比较并递增，防止并发操作静默覆盖彼此。 */
    @Version
    @Column(nullable=false)
    Long version;

    /** 供 JPA 通过反射创建实体，字段由持久化映射填充。 */
    public HttpRequestEntity() { }

    /** HTTP 幂等记录的复合主键；请求键在每个用户范围内唯一。 */
    public static class Id implements Serializable {
        /** 所属用户 ID，用于标识业务归属与限定资源访问范围。 */
        public String userId;
        /** 当前用户范围内的 HTTP 幂等请求 UUID。 */
        public String requestId;

        /** 供 JPA 反射创建复合主键实例。 */
        public Id() { }
        /** 用完整键字段创建可用于仓储查询的复合主键。 */
        public Id(String userId,String requestId) { this.userId=userId; this.requestId=requestId; }

        /** 按全部复合主键字段比较身份，保证 JPA 与集合中的相等性判断一致。 */
        @Override public boolean equals(Object other) {
            return other instanceof Id id && Objects.equals(userId,id.userId) && Objects.equals(requestId,id.requestId);
        }
        /** 使用与 equals 相同的主键字段计算哈希，满足复合主键对象契约。 */
        @Override public int hashCode() { return Objects.hash(userId,requestId); }
    }
}
