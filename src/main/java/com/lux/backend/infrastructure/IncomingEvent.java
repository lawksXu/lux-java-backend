package com.lux.backend.infrastructure;

import com.lux.backend.chat.ConversationContent;
import com.lux.backend.chat.SessionChange;
import com.lux.backend.task.ResultSource;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** 入站已知结构的强类型投影；原信封只作为不可变证据，不参与匿名业务字段解释。 */
public final class IncomingEvent {
    private IncomingEvent() {
    }

    public record ExecutionHeader(int schemaVersion, String eventId, String eventType, String occurredAt,
        String userId, String sessionId, long eventSeq) {
    }

    public record ExecutionFact(ExecutionHeader header, ExecutionBody body, JsonNode originalEnvelope) {
    }

    /** 只读同步返回来源水位和独立完整快照；水位不是 Java 已收到的事件进度。 */
    public record SessionExecutionSync(String userId, String sessionId, long eventHighWatermark, List<ExecutionFact> events,
        List<RunSnapshot> runs, List<TurnSnapshot> turns, List<AgentItemSnapshot> items, Long nextAfterEventSeq) {
    }

    public record RunIdentity(String id, long runIndex) {
    }

    public record TurnIdentity(String id, long turnIndex, RunIdentity run) {
    }

    public record AgentItemIdentity(String id, long itemIndex, TurnIdentity turn) {
    }

    public sealed interface ExecutionBody permits RunSnapshot, TurnSnapshot, AgentItemSnapshot, ModelResponseCompleted,
        ItemTextDelta {
    }

    public record RunSnapshot(RunIdentity run, long sourceRevision, String status, Instant startedAt, Instant finishedAt,
        AgentItemIdentity finalItem, String errorCode, String errorMessage) implements ExecutionBody {
    }

    public record TurnSnapshot(TurnIdentity turn, long sourceRevision, String status, Instant startedAt, Instant finishedAt,
        String errorCode, String errorMessage) implements ExecutionBody {
    }

    public record AgentItemSnapshot(AgentItemIdentity item, String source, long sourceRevision, String kind,
        String status, ConversationContent.Payload payload, Instant completedAt, String callId, String toolName) implements ExecutionBody {
    }

    public record ModelResponseCompleted(String modelExecutionId, TurnIdentity turn, List<AgentItemSnapshot> items) implements ExecutionBody {
        public ModelResponseCompleted {
            items = List.copyOf(items);
        }
    }

    public record ItemTextDelta(AgentItemIdentity item, long streamSeq, String text) implements ExecutionBody,
        SessionChange.ItemBody {
    }

    /** 生成观察没有 Run/Turn 所有权；legacy UNKNOWN 只是查询未确认，不是第五种状态。 */
    public record Header(int schemaVersion, String eventId, String eventType, String userId, String sessionId,
        String taskId, long sequenceNo, Instant occurredAt) {
    }

    public record Generation(Header header, ConnectorExecutionStatus connectorStatus, GenerationOutcome outcome,
        boolean queryUnknown) {
    }

    public enum ConnectorExecutionStatus {
        ACCEPTED, RUNNING, SUCCEEDED, FAILED
    }

    public sealed interface GenerationOutcome permits GenerationSuccessOutcome, GenerationFailureOutcome {
    }

    public record GenerationSuccessOutcome(String status, List<ResultSource> sources) implements GenerationOutcome {
        public GenerationSuccessOutcome {
            sources = List.copyOf(sources);
        }
    }

    public record GenerationFailureOutcome(String status, GenerationError error) implements GenerationOutcome {
    }

    public record GenerationError(String code, String stage, String message, ProviderErrorDiagnostic providerDiagnostic) {
    }

    public record ProviderErrorDiagnostic(String providerId, String providerCode, String providerMessage) {
    }
}
