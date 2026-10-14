package com.lux.backend.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

/** 分页使用登记位置，补齐使用输入顺序，两个索引不相互替代。 */
public interface ItemJpaRepository extends JpaRepository<ItemEntity, String> {
    Optional<ItemEntity> findByUserIdAndSessionIdAndId(String userId, String sessionId, String id);
    Optional<ItemEntity> findByUserIdAndTaskId(String userId, String taskId);
    Optional<ItemEntity> findByTurnIdAndItemIndex(String turnId, long itemIndex);
    Optional<ItemEntity> findByUserIdAndTurnIdAndKindAndCallId(String userId, String turnId, String kind,
        String callId);
    List<ItemEntity> findByUserIdAndSessionIdAndSequenceNoLessThanOrderBySequenceNoDesc(String userId, String sessionId,
        long before, Pageable page);
    List<ItemEntity> findByUserIdAndSessionIdAndInputSequenceNoGreaterThanAndInputSequenceNoLessThanEqualOrderByInputSequenceNo(String userId,
        String sessionId, long after, long through, Pageable page);
    @Query(value = "select input_sequence_no from item where user_id=:userId and session_id=:sessionId and input_sequence_no is not null order by input_sequence_no desc limit 1 for update",
        nativeQuery = true) Optional<Long> latestInputForMutation(String userId, String sessionId);
    @Query("select max(i.inputSequenceNo) from ConversationItem i where i.userId=:userId and i.sessionId=:sessionId")
    Long inputHighWater(String userId, String sessionId);
}
