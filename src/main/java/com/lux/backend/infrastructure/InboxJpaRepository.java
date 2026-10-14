package com.lux.backend.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Inbox 原子接收、版本协调及到期重试查询；重复事件不覆盖首次保存的信封。
 */
public interface InboxJpaRepository extends JpaRepository<InboxEntity, InboxEntity.Id> {
    /**
     * 首次接收时保存 RECEIVED 记录，数据库唯一键防止重复插入覆盖原事件。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO inbox_event (source,event_id,user_id,event_hash,envelope,status)
        VALUES (:source,:id,:owner,:hash,:envelope,'RECEIVED')
        ON DUPLICATE KEY UPDATE event_id=event_id
        """, nativeQuery = true)
    void insertIfAbsent(
        @Param("source") String source,
        @Param("id") String id,
        @Param("owner") String owner,
        @Param("hash") String hash,
        @Param("envelope") String envelope
    );

    /**
     * 保存无法接入处理链的诊断事件，并直接标为 REJECTED。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO inbox_event (source,event_id,user_id,event_hash,envelope,status,error_message,processed_at)
        VALUES (:source,:id,:owner,:hash,:envelope,'REJECTED',:error,UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE event_id=event_id
        """, nativeQuery = true)
    void insertRejectedIfAbsent(
        @Param("source") String source,
        @Param("id") String id,
        @Param("owner") String owner,
        @Param("hash") String hash,
        @Param("envelope") String envelope,
        @Param("error") String error
    );

    /**
     * 比较并递增 Inbox 版本，令业务应用和状态更新在同一事务中受并发保护。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        UPDATE inbox_event SET version=version+1
        WHERE source=:source AND event_id=:id AND version=:version
        """, nativeQuery = true)
    int coordinate(
        @Param("source") String source,
        @Param("id") String id,
        @Param("version") long version
    );

    /**
     * 按接收时间选出最多 100 条指定状态且已到重试时间的记录。
     */
    List<InboxEntity> findFirst100ByStatusAndNextAttemptAtLessThanEqualOrderByReceivedAtAsc(
        String status,
        Instant now
    );
}
