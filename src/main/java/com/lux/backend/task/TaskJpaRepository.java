package com.lux.backend.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 用户范围查询；失联查询只涉及生成前段，不扫描 IMPORTING 来启动下载。 */
interface TaskJpaRepository extends JpaRepository<TaskEntity, String> {
    Optional<TaskEntity> findByUserIdAndOperationId(String userId, String operationId);
    Optional<TaskEntity> findByUserIdAndId(String userId, String id);
    List<TaskEntity> findByUserIdAndIdIn(String userId, Collection<String> ids);
    boolean existsByUserIdAndStatus(String userId, String status);
    @Query("select task from TaskEntity task where task.status in ('CREATED','SUBMITTED','RUNNING') "
        + "and task.updatedAt < :cutoff order by task.updatedAt, task.id")
    List<TaskEntity> staleTasks(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Query("select task from TaskEntity task where task.status in ('CREATED','SUBMITTED','RUNNING') "
        + "and task.updatedAt < :cutoff and (task.updatedAt > :afterUpdatedAt "
        + "or (task.updatedAt = :afterUpdatedAt and task.id > :afterId)) order by task.updatedAt, task.id")
    List<TaskEntity> staleTasksAfter(@Param("cutoff") Instant cutoff, @Param("afterUpdatedAt") Instant afterUpdatedAt,
                                    @Param("afterId") String afterId, Pageable pageable);
}
