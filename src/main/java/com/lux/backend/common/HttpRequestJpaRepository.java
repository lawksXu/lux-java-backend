package com.lux.backend.common;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * HTTP 幂等回执访问接口，通过数据库唯一键原子地建立首次请求记录。
 */
public interface HttpRequestJpaRepository extends JpaRepository<HttpRequestEntity, HttpRequestEntity.Id> {
    /**
     * 利用用户与请求 ID 的唯一键原子插入回执；重复请求保留原摘要和响应。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO http_request (user_id,request_id,request_hash) VALUES (:user,:key,:hash)
        ON DUPLICATE KEY UPDATE request_id=request_id
        """, nativeQuery = true)
    void insertIfAbsent(
        @Param("user") String user,
        @Param("key") String key,
        @Param("hash") String hash
    );
}
