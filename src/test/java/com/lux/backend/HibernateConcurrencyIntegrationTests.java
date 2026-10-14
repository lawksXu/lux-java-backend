package com.lux.backend;

import com.lux.backend.chat.*;
import com.lux.backend.common.Ids;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.common.Timestamps;
import com.lux.backend.infrastructure.CommandPayload;
import com.lux.backend.infrastructure.IncomingEvent.*;
import com.lux.backend.infrastructure.OutboxRepository;
import com.lux.backend.infrastructure.Topics;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HibernateConcurrencyIntegrationTests extends IntegrationSupport {
    @Autowired SessionService sessions;
    @Autowired ChatRepository chats;
    @Autowired RunEventService projections;
    @Autowired OutboxRepository outbox;
    @Autowired JsonCodec json;
    @Autowired JdbcClient jdbc;
    @Autowired EntityManagerFactory factory;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager manager;

    @Test
    void managedProjectionVersionsPreventLostUpdatesWithoutChangingSourceRevision() {
        ChatModels.SessionRecord session = sessions.create(USER, "Projection version conflict");
        String itemId = streamedItem(session.id());
        ChatModels.ItemProjection initial = chats.item(USER, session.id(), itemId);
        try (EntityManager first = factory.createEntityManager(); EntityManager second = factory.createEntityManager()) {
            first.getTransaction().begin();
            second.getTransaction().begin();
            ItemEntity a = first.find(ItemEntity.class, itemId);
            ItemEntity b = second.find(ItemEntity.class, itemId);
            ReflectionTestUtils.setField(a, "payload", json.write(new ConversationContent.OrderedContent(List.of(new ContentBlock.Text("Winner")))));
            first.getTransaction().commit();
            ReflectionTestUtils.setField(b, "payload", json.write(new ConversationContent.OrderedContent(List.of(new ContentBlock.Text("Loser")))));
            RuntimeException failure = assertThrows(RuntimeException.class, () -> second.getTransaction().commit());
            assertTrue(hasOptimisticCause(failure));
            if (second.getTransaction().isActive()) second.getTransaction().rollback();
        }
        ChatModels.ItemProjection current = chats.item(USER, session.id(), itemId);
        assertEquals("Winner", ((ContentBlock.Text) ((ConversationContent.OrderedContent) current.payload()).blocks().get(0)).text());
        assertTrue(current.version() > initial.version());
        assertEquals(initial.sourceRevision(), current.sourceRevision());
    }

    @Test
    void aLateProjectionVersionConflictRollsBackIndependentInputOutboxAndSse() {
        ChatModels.SessionRecord session = sessions.create(USER, "Atomic rollback");
        String existingId = streamedItem(session.id());
        String rejectedInputId = Ids.next();
        long eventBefore = chats.lastEventSequence(USER, session.id());
        long registrationBefore = chats.session(USER, session.id()).nextItemSeq();
        TransactionTemplate transaction = new TransactionTemplate(manager);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
            ItemEntity stale = entities.find(ItemEntity.class, existingId);
            try (EntityManager winner = factory.createEntityManager()) {
                winner.getTransaction().begin();
                ItemEntity changed = winner.find(ItemEntity.class, existingId);
                ReflectionTestUtils.setField(changed, "payload", json.write(new ConversationContent.OrderedContent(List.of(new ContentBlock.Text("Winner")))));
                winner.getTransaction().commit();
            }
            chats.sessionForMutation(USER, session.id());
            ChatModels.AcceptedInputItem accepted = chats.insertInput(USER, session.id(), rejectedInputId, "USER", "MESSAGE",
                new ConversationContent.OrderedContent(List.of(new ContentBlock.Text("New independent input"))), null);
            outbox.enqueueEnvelope(Topics.AGENT_COMMANDS, USER + ":" + session.id(),
                new CommandPayload.InputAccepted(2, accepted.id(), "INPUT_ACCEPTED", Timestamps.now().toString(), USER, session.id(), accepted));
            chats.event(USER, session.id(), "ITEM_CREATED", new SessionChange.SessionItemNotification(accepted.id(), accepted.sequenceNo(), chats.item(USER, session.id(), accepted.id())));
            ReflectionTestUtils.setField(stale, "payload", json.write(new ConversationContent.OrderedContent(List.of(new ContentBlock.Text("Loser")))));
            entities.flush();
        }));
        assertTrue(hasOptimisticCause(failure));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM item WHERE id=?").param(rejectedInputId).query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE event_id=?").param(rejectedInputId).query(Integer.class).single());
        assertEquals(eventBefore, chats.lastEventSequence(USER, session.id()));
        assertEquals(registrationBefore, chats.session(USER, session.id()).nextItemSeq());
    }

    @Test
    void concurrentInputAcceptanceAllocatesConsecutivePositionsFromCurrentReadsUnderRepeatableRead() throws Exception {
        ChatModels.SessionRecord session = sessions.create(USER, "Concurrent current reads");
        int count = 6;
        CountDownLatch snapshots = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        List<Future<ChatModels.InputAcceptanceResponse>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                futures.add(pool.submit(() -> {
                    TransactionTemplate transaction = new TransactionTemplate(manager);
                    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    return transaction.execute(status -> {
                        // 先建立较早 MVCC 快照；分配必须使用锁定后的当前索引，不是这个快照的 MAX。
                        chats.session(USER, session.id());
                        snapshots.countDown();
                        try {
                            assertTrue(start.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return sessions.send(USER, session.id(), Ids.next(), List.of(new ContentBlock.Text("Input")));
                    });
                }));
            }
            assertTrue(snapshots.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<ChatModels.InputAcceptanceResponse> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), chats.inputs(USER, session.id(), 0, count, count).stream().map(ChatModels.AcceptedInputItem::inputSequenceNo).toList());
        assertEquals(count, chats.lastEventSequence(USER, session.id()));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM agent_run WHERE session_id=?").param(session.id()).query(Integer.class).single());
        assertEquals(count, sessions.repairInputs(USER, session.id(), 0, count, count).size());
    }

    private String streamedItem(String session) {
        RunIdentity run = new RunIdentity(Ids.next(), 1);
        TurnIdentity turn = new TurnIdentity(Ids.next(), 1, run);
        AgentItemIdentity item = new AgentItemIdentity(Ids.next(), 1, turn);
        AgentItemSnapshot snapshot = new AgentItemSnapshot(item, "AGENT", 1, "MESSAGE", "PARTIAL", new ConversationContent.OrderedContent(List.of()), null, null, null);
        String event = Ids.next();
        String time = Timestamps.now().toString();
        projections.applyEvent(new ExecutionFact(new ExecutionHeader(2, event, "AGENT_ITEM_SNAPSHOT", time, USER, session, 1), snapshot,
            json.tree(new SourceEnvelope(2, event, "AGENT_ITEM_SNAPSHOT", time, USER, session, 1, snapshot))));
        return item.id();
    }

    private record SourceEnvelope(int schemaVersion, String eventId, String eventType, String occurredAt,
        String userId, String sessionId, long eventSeq, ExecutionBody body) {}

    private boolean hasOptimisticCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockException || cause instanceof OptimisticLockingFailureException) return true;
        }
        return false;
    }
}
