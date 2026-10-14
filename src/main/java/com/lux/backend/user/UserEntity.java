package com.lux.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** user 表的 JPA 映射；版本号还用于资源和任务创建时的用户级写入协调。 */
@Entity
@Table(name = "`user`")
public class UserEntity {
    /** 用户的唯一 UUID 身份。 */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36, nullable = false, columnDefinition = "char(36)")
    String id;

    /** 用户展示名称，与用户唯一身份 ID 分开维护。 */
    @Column(name = "display_name", length = 128, nullable = false)
    String displayName;

    /** Hibernate 乐观锁版本；更新时比较并递增，防止并发操作静默覆盖彼此。 */
    @Version
    @Column(name = "version", nullable = false)
    Long version = 1L;

    /** 由数据库默认值生成的创建时间，JPA 插入或更新时不写入该列。 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "datetime(6)")
    Instant createdAt;

    /** 由数据库维护的最近更新时间，JPA 不直接写入该列。 */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "datetime(6)")
    Instant updatedAt;

    /** 供 JPA 通过反射创建实体，字段由持久化映射填充。 */
    protected UserEntity() { }
}
