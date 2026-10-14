package com.lux.backend.chat;

import com.lux.backend.common.JsonCodec;
import com.lux.backend.infrastructure.EventProtocol;
import com.lux.backend.infrastructure.IncomingEvent.ExecutionFact;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原始事件身份、片段位置和跨 Session 观察起点是可恢复性的业务边界。 */
class ChatJournalTests {
    private final SessionJpaRepository sessions = mock(SessionJpaRepository.class);
    private final RunEventJpaRepository journal = mock(RunEventJpaRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final ChatRepository chats = new ChatRepository(sessions, mock(ItemJpaRepository.class),
        mock(RunJpaRepository.class), mock(TurnJpaRepository.class), journal,
        mock(SessionEventJpaRepository.class), entities, json, mock(ContentValidator.class));

    @Test
    void initialObservationUsesAnActualUuidLowerBoundInsteadOfSqlNull() {
        chats.observationSessions(null, 20);
        verify(sessions).findByIdGreaterThanOrderById(eq(""), any());
    }

    @Test
    void anotherEventIdCannotReuseAStreamPositionEvenWithTheSameText() throws Exception {
        ObjectNode original = fixture();
        ExecutionFact first = EventProtocol.agent(original);
        RunEventEntity row = row(first);
        when(journal.findByItemIdAndStreamSeq("00000000-0000-0000-0000-000000000400", 1))
            .thenReturn(Optional.of(row));
        ObjectNode duplicatePosition = original.deepCopy();
        duplicatePosition.put("event_id", "00000000-0000-0000-0000-000000000599").put("event_seq", 5);
        assertThatThrownBy(() -> chats.journal(EventProtocol.agent(duplicatePosition)))
            .isInstanceOf(IllegalArgumentException.class);
        verify(entities, never()).persist(any());
    }

    @Test
    void retryOfTheOriginalEventDoesNotInsertAnotherJournalEntry() throws Exception {
        ExecutionFact fact = EventProtocol.agent(fixture());
        when(journal.findById(fact.header().eventId())).thenReturn(Optional.of(row(fact)));
        chats.journal(fact);
        verify(entities, never()).persist(any());
    }

    @Test
    void corruptedIndexedRoutingCannotBeUsedToReplayAnotherItemsOriginalEnvelope() throws Exception {
        ExecutionFact fact = EventProtocol.agent(fixture());
        RunEventEntity corrupted = row(fact);
        corrupted.itemId = "00000000-0000-0000-0000-000000000499";
        when(journal.findByUserIdAndSessionIdAndSequenceNoGreaterThanOrderBySequenceNo(
            eq(fact.header().userId()), eq(fact.header().sessionId()), eq(0L), any()))
            .thenReturn(List.of(corrupted));
        assertThatThrownBy(() -> chats.executionEvents(fact.header().userId(), fact.header().sessionId(), 0, 100))
            .hasMessageContaining("journal Item routing");
    }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) json.read(Files.readString(Path.of("docs/fixtures/v2/item-delta.json")));
    }

    private RunEventEntity row(ExecutionFact fact) {
        RunEventEntity row = new RunEventEntity();
        row.id = fact.header().eventId();
        row.userId = fact.header().userId();
        row.sessionId = fact.header().sessionId();
        row.runId = "00000000-0000-0000-0000-000000000200";
        row.itemId = "00000000-0000-0000-0000-000000000400";
        row.streamSeq = 1L;
        row.sequenceNo = fact.header().eventSeq();
        row.eventType = fact.header().eventType();
        row.occurredAt = Instant.parse(fact.header().occurredAt());
        row.eventHash = json.hash(fact.originalEnvelope());
        row.envelope = json.write(fact.originalEnvelope());
        return row;
    }
}
