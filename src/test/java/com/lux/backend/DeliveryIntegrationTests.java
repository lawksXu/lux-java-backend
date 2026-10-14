package com.lux.backend;

import com.lux.backend.chat.*;
import com.lux.backend.common.Ids;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.common.Timestamps;
import com.lux.backend.infrastructure.*;
import com.lux.backend.infrastructure.IncomingEvent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DeliveryIntegrationTests extends IntegrationSupport {
    @Autowired SessionService sessions;
    @Autowired ChatRepository chats;
    @Autowired RunEventService projections;
    @Autowired JsonCodec json;
    @Autowired JdbcClient jdbc;
    @Autowired InboxRepository inbox;
    @Autowired InboxProcessor processor;
    @Autowired OutboxRepository outbox;

    @Test
    void concurrentProcessingOfOneInboxIdentityAppendsAndNotifiesOnce() throws Exception {
        ChatModels.SessionRecord session = sessions.create(USER, "Concurrent Inbox identity");
        AgentItemIdentity item = new AgentItemIdentity(Ids.next(), 1, new TurnIdentity(Ids.next(), 1, new RunIdentity(Ids.next(), 1)));
        AgentItemSnapshot initial = new AgentItemSnapshot(item, "AGENT", 1, "MESSAGE", "PARTIAL", new ConversationContent.OrderedContent(List.of()), null, null, null);
        String first = Ids.next();
        String time = Timestamps.now().toString();
        projections.applyEvent(new ExecutionFact(new ExecutionHeader(2, first, "AGENT_ITEM_SNAPSHOT", time, USER, session.id(), 1), initial,
            json.tree(new SourceEnvelope(2, first, "AGENT_ITEM_SNAPSHOT", time, USER, session.id(), 1, initial))));
        String id = Ids.next();
        ItemTextDelta delta = new ItemTextDelta(item, 1, "Only once");
        assertTrue(inbox.receive(Topics.AGENT_EVENTS, id, USER,
            json.tree(new SourceEnvelope(2, id, "ITEM_TEXT_DELTA", time, USER, session.id(), 90, delta))));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> firstAttempt = pool.submit(() -> { processTogether(id, ready, start); return null; });
            Future<Void> secondAttempt = pool.submit(() -> { processTogether(id, ready, start); return null; });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            firstAttempt.get(15, TimeUnit.SECONDS);
            secondAttempt.get(15, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        assertEquals("APPLIED", jdbc.sql("SELECT status FROM inbox_event WHERE source=? AND event_id=?").params(Topics.AGENT_EVENTS, id).query(String.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM run_event WHERE id=?").param(id).query(Integer.class).single());
        ConversationContent.OrderedContent content = (ConversationContent.OrderedContent) chats.item(USER, session.id(), item.id()).payload();
        assertEquals(List.of(new ContentBlock.Text("Only once")), content.blocks());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM session_event WHERE session_id=? AND event_type='ITEM_TEXT_DELTA'").param(session.id()).query(Integer.class).single());
        assertEquals(List.of(1L, 90L), chats.receivedEventSequences(USER, session.id()));
    }

    @Test
    void pendingTypedOutboxPreservesKeyOrderingAcrossRetryAndAcknowledgment() {
        ChatModels.SessionRecord session = sessions.create(USER, "Outbox ordering");
        String key = USER + ":" + session.id();
        String first = enqueue("integration.first", key, session.id());
        String second = enqueue("integration.first", key, session.id());
        String third = enqueue("integration.first", key, session.id());
        String otherKey = enqueue("integration.first", key + ":other", session.id());
        String otherTopic = enqueue("integration.second", key, session.id());
        List<OutboxRepository.Pending> initial = pending(session.id());
        assertEquals(Set.of(first, otherKey, otherTopic), identities(initial));
        OutboxRepository.Pending head = initial.stream().filter(event -> identity(event).equals(first)).findFirst().orElseThrow();
        outbox.failed(head, "Broker unavailable");
        jdbc.sql("UPDATE outbox_event SET next_attempt_at=TIMESTAMPADD(MINUTE,5,UTC_TIMESTAMP(6)) WHERE id=?").param(head.id()).update();
        assertEquals(Set.of(otherKey, otherTopic), identities(pending(session.id())));
        outbox.delivered(head.id());
        List<OutboxRepository.Pending> afterFirst = pending(session.id());
        assertEquals(Set.of(second, otherKey, otherTopic), identities(afterFirst));
        outbox.delivered(afterFirst.stream().filter(event -> identity(event).equals(second)).findFirst().orElseThrow().id());
        assertEquals(Set.of(third, otherKey, otherTopic), identities(pending(session.id())));
    }

    private String enqueue(String topic, String key, String session) {
        String id = Ids.next();
        outbox.enqueueEnvelope(topic, key, new CommandPayload.ContinueSession(2, id, "CONTINUE_SESSION", Timestamps.now().toString(), USER, session));
        return id;
    }

    private void processTogether(String id, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        processor.process(Topics.AGENT_EVENTS, id);
    }

    private List<OutboxRepository.Pending> pending(String session) {
        return outbox.pending().stream().filter(event -> json.read(event.envelope()).path("session_id").asString().equals(session)).toList();
    }

    private Set<String> identities(List<OutboxRepository.Pending> events) {
        return events.stream().map(this::identity).collect(Collectors.toSet());
    }

    private String identity(OutboxRepository.Pending event) {
        return json.read(event.envelope()).path("event_id").asString();
    }

    private record SourceEnvelope(int schemaVersion, String eventId, String eventType, String occurredAt,
        String userId, String sessionId, long eventSeq, ExecutionBody body) {}
}
