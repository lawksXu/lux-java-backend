package com.lux.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/** Turn 身份及父引用采用真实 Python 索引，来源快照独立推进。 */
public interface TurnJpaRepository extends JpaRepository<TurnEntity, String> {
    Optional<TurnEntity> findByUserIdAndSessionIdAndId(String userId, String sessionId, String id);
    Optional<TurnEntity> findByRunIdAndTurnIndex(String runId, long turnIndex);
    List<TurnEntity> findByUserIdAndSessionId(String userId, String sessionId);
}
