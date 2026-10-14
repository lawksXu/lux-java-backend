package com.lux.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 用户 Spring Data 接口，提供按版本号比较并递增的原子协调更新。 */
public interface UserJpaRepository extends JpaRepository<UserEntity, String> {
    /** 只有版本一致才原子递增版本；更新行数为零表示并发状态已经变化。 */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.version = u.version + 1 WHERE u.id = :id AND u.version = :version")
    int coordinate(@Param("id") String id, @Param("version") long version);
}
