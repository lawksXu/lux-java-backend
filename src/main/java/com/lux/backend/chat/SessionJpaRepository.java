package com.lux.backend.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/** Session 归属查询与分页；写锁当前读由 ChatRepository 直接刷新受管实体。 */
public interface SessionJpaRepository extends JpaRepository<SessionEntity, String> {
    Optional<SessionEntity> findByUserIdAndId(String userId, String id);
    List<SessionEntity> findByUserIdOrderByCreatedAtDescIdDesc(String userId, Pageable page);
    List<SessionEntity> findByIdGreaterThanOrderById(String after, Pageable page);
}
