package com.lux.backend.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.lux.backend.infrastructure.EventProtocol;
import com.lux.backend.infrastructure.IncomingEvent;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** Java 业务投影与 HTTP 快照；执行归属只表达 Python 来源，不领取 Session 输入。 */
public final class ChatModels {
    private ChatModels() {
    }

    public record SessionRecord(String id, String userId, String title, long nextItemSeq, long version, Instant createdAt,
        Instant updatedAt) {
    }

    public record ItemProjection(String id, String userId, String sessionId, long sequenceNo, String source,
        String kind, String runId, String turnId, Long itemIndex, Long inputSequenceNo, String status, String callId,
        String toolName, String taskId, ConversationContent.Payload payload, long sourceRevision, Instant completedAt,
        long version, Instant createdAt, Instant updatedAt) implements SessionChange.ItemBody {
    }

    public record RunProjection(String id, String userId, String sessionId, long runIndex, long sourceRevision,
        String status, Instant startedAt, Instant finishedAt, String finalItemId, String errorCode, String errorMessage,
        Instant lastSeenAt, long version, Instant createdAt, Instant updatedAt) {
    }

    public record TurnProjection(String id, String userId, String sessionId, String runId, long turnIndex,
        long sourceRevision, String status, Instant startedAt, Instant finishedAt, String errorCode, String errorMessage,
        long version, Instant createdAt, Instant updatedAt) {
    }

    public record SessionProjectionPage(List<ItemProjection> items, List<RunProjection> runs, List<TurnProjection> turns,
        Long nextBeforeSequenceNo, long lastEventSequenceNo) {
    }

    public record AcceptedInputItem(String id, long sequenceNo, long inputSequenceNo, String source, String kind,
        ConversationContent.Payload content) {
        /** HTTP 回执重放也复用相同的输入形状校验，不把模型输出解码成输入。 */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static AcceptedInputItem decode(JsonNode value) {
            return EventProtocol.acceptedItem(value);
        }
    }

    public record InputAcceptanceResponse(AcceptedInputItem item) {
    }

    public record ControlAcceptanceResponse(String eventId) {
    }

    public record SessionEvent(long sequenceNo, String eventType, SessionChange payload) {
    }

    public record RunEvent(String id, long sequenceNo, String eventType, IncomingEvent.ExecutionFact envelope) {
    }
}
