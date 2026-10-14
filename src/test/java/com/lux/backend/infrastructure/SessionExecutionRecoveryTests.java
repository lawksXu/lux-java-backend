package com.lux.backend.infrastructure;

import com.lux.backend.chat.ChatModels;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.chat.RunEventService;
import com.lux.backend.common.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionExecutionRecoveryTests {
    private final ChatRepository chats = mock(ChatRepository.class);
    private final RunEventService facts = mock(RunEventService.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final JsonCodec json = new JsonCodec(JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());

    @Test
    void missingMiddleFactCannotBeHiddenByALargerObservedSequence() {
        assertEquals(0, SessionExecutionRecovery.contiguousPrefix(List.of(3L, 4L)));
        assertEquals(2, SessionExecutionRecovery.contiguousPrefix(List.of(1L, 1L, 2L, 4L, 9L)));
        assertEquals(3, SessionExecutionRecovery.contiguousPrefix(List.of(1L, 2L, 3L)));
    }

    @Test
    void absentObservationUrlNeverMakesAnExecutionDecision() {
        new SessionExecutionRecovery(chats, facts, outbox, mock(TransactionTemplate.class), json, "").reconcileStale();
        verifyNoInteractions(chats, facts, outbox);
    }

    @Test
    void anUnrepairableLowGapCannotStarveLaterSourcePages() throws Exception {
        String owner = "00000000-0000-0000-0000-000000000001";
        String session = "00000000-0000-0000-0000-000000000010";
        ChatModels.SessionRecord record = new ChatModels.SessionRecord(session, owner, "Paged recovery", 0, 0,
                Instant.EPOCH, Instant.EPOCH);
        when(chats.observationSessions(nullable(String.class), eq(20))).thenReturn(List.of(record));
        when(chats.receivedEventSequences(owner, session)).thenReturn(List.of(1L, 3L));
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        TransactionTemplate transactions = new TransactionTemplate(manager);
        java.util.List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();
        tools.jackson.databind.node.ObjectNode fact = (tools.jackson.databind.node.ObjectNode) json.read(
                java.nio.file.Files.readString(java.nio.file.Path.of("docs/fixtures/v2/run-snapshot.json")));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v2/users/" + owner + "/sessions/" + session + "/execution", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            boolean first = queries.size() == 1;
            tools.jackson.databind.node.ObjectNode event = fact.deepCopy().put("event_seq", first ? 100 : 201);
            tools.jackson.databind.node.ObjectNode value = json.object().put("user_id", owner).put("session_id", session)
                    .put("event_high_watermark", 201);
            value.set("events", json.array().add(event));
            value.set("runs", json.array()); value.set("turns", json.array()); value.set("items", json.array());
            if (first) value.put("next_after_event_seq", 100); else value.putNull("next_after_event_seq");
            byte[] body = json.write(value).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            SessionExecutionRecovery recovery = new SessionExecutionRecovery(chats, facts, outbox, transactions, json,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            recovery.reconcileStale();
            recovery.reconcileStale();
            assertEquals(List.of("after_event_seq=1&limit=100", "after_event_seq=100&through_event_seq=201&limit=100"), queries);
            verify(facts).synchronize(argThat(sync -> sync.events().get(0).header().eventSeq() == 201));
            verify(outbox, times(2)).enqueueEnvelope(eq(Topics.AGENT_COMMANDS), eq(owner + ":" + session),
                    argThat(command -> command instanceof CommandPayload.ReplaySessionEvents replay && replay.afterEventSeq() == 1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sourceSnapshotCanBeAppliedBeforeGapReplayWithoutInventingLifecycleFacts() throws Exception {
        String owner = "00000000-0000-0000-0000-000000000001";
        String session = "00000000-0000-0000-0000-000000000010";
        ChatModels.SessionRecord record = new ChatModels.SessionRecord(session, owner, "Recovery", 0, 0,
                Instant.EPOCH, Instant.EPOCH);
        when(chats.observationSessions(null, 20)).thenReturn(List.of(record));
        when(chats.receivedEventSequences(owner, session)).thenReturn(List.of(1L, 3L));
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        TransactionTemplate transactions = new TransactionTemplate(manager);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v2/users/" + owner + "/sessions/" + session + "/execution", exchange -> {
            assertEquals("after_event_seq=1&limit=100", exchange.getRequestURI().getRawQuery());
            byte[] body = ("{\"user_id\":\"" + owner + "\",\"session_id\":\"" + session
                    + "\",\"event_high_watermark\":3,\"events\":[],\"runs\":[],\"turns\":[],\"items\":[],\"next_after_event_seq\":null}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            new SessionExecutionRecovery(chats, facts, outbox, transactions, json,
                    "http://127.0.0.1:" + server.getAddress().getPort()).reconcileStale();
            verify(facts).synchronize(argThat(sync -> sync.eventHighWatermark() == 3 && sync.events().isEmpty()));
            verify(facts).recoverSessionProjection(owner, session);
            verify(outbox).enqueueEnvelope(eq(Topics.AGENT_COMMANDS), eq(owner + ":" + session),
                    argThat(command -> command instanceof CommandPayload.ReplaySessionEvents replay
                            && replay.afterEventSeq() == 1 && replay.throughEventSeq() == 3));
            verify(facts, never()).applyEvent(any());
        } finally {
            server.stop(0);
        }
    }
}
