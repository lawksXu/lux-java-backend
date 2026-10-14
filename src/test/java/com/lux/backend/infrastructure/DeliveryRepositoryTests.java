package com.lux.backend.infrastructure;

import com.lux.backend.common.JsonCodec;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryRepositoryTests {
    private final JsonCodec json=new JsonCodec(JsonMapper.builder().build());
    private final EntityManager entities=mock(EntityManager.class);
    private final InboxJpaRepository inboxRows=mock(InboxJpaRepository.class);
    private final InboxRepository inbox=new InboxRepository(inboxRows,entities,json);

    @Test void aStaleInboxVersionStopsProcessingBeforeAnyBusinessEffect() {
        InboxEntity entry=received();
        when(inboxRows.findById(new InboxEntity.Id(entry.source,entry.eventId))).thenReturn(Optional.of(entry));
        when(inboxRows.coordinate(entry.source,entry.eventId,entry.version)).thenReturn(0);

        assertThrows(ObjectOptimisticLockingFailureException.class,
                ()->inbox.forProcessing(entry.source,entry.eventId));

        verify(entities,never()).refresh(entry);
    }

    @Test void completedInboxReceiptCannotBeDowngradedByLateFailureHandling() {
        InboxEntity entry=received();
        entry.status="APPLIED";
        entry.processedAt=Instant.now();
        when(inboxRows.findById(new InboxEntity.Id(entry.source,entry.eventId))).thenReturn(Optional.of(entry));

        inbox.rejected(entry.source,entry.eventId,"late rejection");
        inbox.retry(entry.source,entry.eventId,"late retry");

        assertEquals("APPLIED",entry.status);
        assertEquals(0,entry.attempts);
        assertNull(entry.errorMessage);
        verify(entities,never()).flush();
    }

    @Test void conflictingDuplicateRetainsTheOriginalEnvelopeAndAppliedState() {
        InboxEntity entry=received();
        entry.status="APPLIED";
        ObjectNode duplicate=json.object().put("payload","different");
        when(inboxRows.findById(new InboxEntity.Id(entry.source,entry.eventId))).thenReturn(Optional.of(entry));

        assertFalse(inbox.receive(entry.source,entry.eventId,"owner",duplicate));

        assertEquals("{}",entry.envelope);
        assertEquals("APPLIED",entry.status);
        assertEquals(duplicate,json.read(entry.conflictEnvelope));
        verify(entities).flush();
    }

    @Test void outboxFailureCannotUndoAnAlreadyConfirmedDelivery() {
        OutboxJpaRepository rows=mock(OutboxJpaRepository.class);
        OutboxRepository outbox=new OutboxRepository(rows,entities,json);
        OutboxEntity entry=new OutboxEntity();
        entry.id=9L;
        entry.deliveredAt=Instant.now();
        when(rows.findById(entry.id)).thenReturn(Optional.of(entry));

        outbox.failed(new OutboxRepository.Pending(entry.id,"topic","owner:session","{}",0),"late failure");

        assertEquals(0,entry.attempts);
        assertNull(entry.lastError);
        verify(entities,never()).flush();
    }

    private InboxEntity received() {
        InboxEntity entry=new InboxEntity();
        entry.source="source";
        entry.eventId="00000000-0000-0000-0000-000000000001";
        entry.status="RECEIVED";
        entry.envelope="{}";
        entry.eventHash=json.hash(json.object());
        entry.version=1L;
        return entry;
    }
}
