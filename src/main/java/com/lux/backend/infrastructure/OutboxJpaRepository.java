package com.lux.backend.infrastructure;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** 筛选到期的 Outbox 记录；同一主题和分区键只允许最早的未送达记录先发送。 */
public interface OutboxJpaRepository extends JpaRepository<OutboxEntity,Long> {
    /** 挑选已到发送时间的最早未送达记录，阻止同一主题和键的后续命令越过失败记录。 */
    @Query("""
            select event from OutboxEntity event
            where event.deliveredAt is null and event.nextAttemptAt<=:now
              and not exists (
                  select prior.id from OutboxEntity prior
                  where prior.topic=event.topic and prior.partitionKey=event.partitionKey
                    and prior.id<event.id and prior.deliveredAt is null
              )
            order by event.id
            """)
    List<OutboxEntity> pending(@Param("now") Instant now,Pageable page);

    /** 原始输入信封随输入历史保留，查询不依赖是否已经投递。 */
    List<OutboxEntity> findByUserIdAndSessionIdAndEventIdInOrderByIdAsc(
            String userId, String sessionId, List<String> eventIds);

    /** 已有稳定事件身份不能写成第二份不同命令。 */
    java.util.Optional<OutboxEntity> findByEventId(String eventId);
}
