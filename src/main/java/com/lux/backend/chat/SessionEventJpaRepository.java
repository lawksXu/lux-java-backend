package com.lux.backend.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

/** SSE 序号来自已保留日志；分配当前读与页面一致性读明确分开。 */
public interface SessionEventJpaRepository extends JpaRepository<SessionEventEntity, SessionEventEntity.Key> {
    List<SessionEventEntity> findByUserIdAndSessionIdAndSequenceNoGreaterThanOrderBySequenceNo(String userId,
        String sessionId, long after, Pageable page);
    @Query(value = "select sequence_no from session_event where user_id=:userId and session_id=:sessionId order by sequence_no desc limit 1 for update",
        nativeQuery = true) Optional<Long> latestForMutation(String userId, String sessionId);
    @Query("select max(e.sequenceNo) from SessionEventEntry e where e.userId=:userId and e.sessionId=:sessionId")
    Long highWater(String userId, String sessionId);
}
