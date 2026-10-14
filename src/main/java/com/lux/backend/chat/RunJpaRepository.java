package com.lux.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/** Run 索引只用于显示与来源身份冲突校验，不建立非终态执行槽位。 */
public interface RunJpaRepository extends JpaRepository<RunEntity, String> {
    Optional<RunEntity> findByUserIdAndId(String userId, String id);
    Optional<RunEntity> findBySessionIdAndRunIndex(String sessionId, long runIndex);
    List<RunEntity> findByUserIdAndSessionIdOrderByRunIndex(String userId, String sessionId);
}
