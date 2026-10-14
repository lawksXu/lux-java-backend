package com.lux.backend.generation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 每个计划查询都同时限定 owner 和 Session，分页使用不可变创建位置。 */
public interface GenerationPlanJpaRepository extends org.springframework.data.jpa.repository.JpaRepository<GenerationPlanEntity, String> {
    Optional<GenerationPlanEntity> findByUserIdAndSessionIdAndId(String owner, String session, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GenerationPlanEntity p where p.userId=:owner and p.sessionId=:session and p.id=:id")
    Optional<GenerationPlanEntity> lock(String owner, String session, String id);
    @Query("select p from GenerationPlanEntity p where p.userId=:owner and p.sessionId=:session "
        + "and (:before is null or p.createdAt<:before or (p.createdAt=:before and p.id<:id)) order by p.createdAt desc,p.id desc")
    List<GenerationPlanEntity> page(String owner, String session, Instant before, String id, Pageable page);
}
