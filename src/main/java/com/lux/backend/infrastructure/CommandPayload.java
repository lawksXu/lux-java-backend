package com.lux.backend.infrastructure;

import com.lux.backend.chat.ChatModels.AcceptedInputItem;
import com.lux.backend.task.ConnectorRequest;

/** Java 受理的具名信封；事件身份和原始时间在受理事务中固定。 */
public interface CommandPayload {
    record Empty() implements CommandPayload {
    }

    public sealed interface Envelope permits InputAccepted, StopSession, ContinueSession, QuerySessionExecution,
        ReplaySessionEvents, GenerationSubmit, TaskStatusQuery {
        int schemaVersion();
        String eventId();
        String eventType();
        String occurredAt();
        String userId();
        String sessionId();
    }

    record InputAccepted(int schemaVersion, String eventId, String eventType, String occurredAt, String userId,
        String sessionId, AcceptedInputItem item) implements Envelope {
    }

    record StopSession(int schemaVersion, String eventId, String eventType, String occurredAt, String userId,
        String sessionId) implements Envelope {
    }

    record ContinueSession(int schemaVersion, String eventId, String eventType, String occurredAt, String userId,
        String sessionId) implements Envelope {
    }

    record QuerySessionExecution(int schemaVersion, String eventId, String eventType, String occurredAt, String userId,
        String sessionId, int limit) implements Envelope {
    }

    record ReplaySessionEvents(int schemaVersion, String eventId, String eventType, String occurredAt, String userId,
        String sessionId, long afterEventSeq, long throughEventSeq, int limit) implements Envelope {
    }

    record GenerationSubmit(int schemaVersion, String eventId, String eventType, String occurredAt, String userId,
        String sessionId, String taskId, String operationId, String kind, ConnectorRequest request) implements Envelope {
    }

    record TaskStatusQuery(int schemaVersion, String eventId, String eventType, String occurredAt, String userId,
        String sessionId, String taskId, long lastObservedSequenceNo) implements Envelope {
    }
}
