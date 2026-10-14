package com.lux.backend.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/** 原始事件证据的幂等定位和 Session 范围重放。 */
public interface RunEventJpaRepository extends JpaRepository<RunEventEntity, String> {
    Optional<RunEventEntity> findBySessionIdAndSequenceNo(String sessionId, long sequenceNo);
    List<RunEventEntity> findByUserIdAndSessionIdAndSequenceNoGreaterThanOrderBySequenceNo(String userId,
        String sessionId, long after, Pageable page);
    List<RunEventEntity> findByUserIdAndSessionIdAndEventType(String userId, String sessionId, String eventType);
    Optional<RunEventEntity> findByItemIdAndStreamSeq(String itemId, long streamSeq);
    List<RunEventEntity> findByUserIdAndSessionIdAndItemIdAndStreamSeqIsNotNullOrderByStreamSeq(
        String userId, String sessionId, String itemId);
    List<RunEventEntity> findByUserIdAndSessionIdOrderBySequenceNo(String userId, String sessionId);
}
