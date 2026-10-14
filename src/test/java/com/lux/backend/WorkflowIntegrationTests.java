package com.lux.backend;

import com.lux.backend.chat.ChatModels;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.chat.ContentBlock;
import com.lux.backend.chat.ConversationContent;
import com.lux.backend.chat.SessionService;
import com.lux.backend.common.ApiException;
import com.lux.backend.common.Ids;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.common.Timestamps;
import com.lux.backend.infrastructure.CommandPayload;
import com.lux.backend.infrastructure.InboxProcessor;
import com.lux.backend.infrastructure.IncomingEvent;
import com.lux.backend.infrastructure.OutboxPublisher;
import com.lux.backend.infrastructure.Topics;
import com.lux.backend.task.TaskService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowIntegrationTests extends IntegrationSupport {
    @Autowired SessionService sessions;
    @Autowired ChatRepository chats;
    @Autowired JsonCodec json;
    @Autowired JdbcClient jdbc;
    @Autowired InboxProcessor inbox;
    @Autowired OutboxPublisher publisher;
    @Autowired TaskService tasks;
    @Autowired MockMvc mvc;
    @Autowired KafkaTemplate<String, String> kafka;

    @BeforeEach
    void secondOwner() {
        jdbc.sql("INSERT INTO `user` (id,display_name) VALUES (?,'Second local user') ON DUPLICATE KEY UPDATE id=id")
                .param(OTHER).update();
    }

    @Test
    void concurrentInputsHaveContiguousIndependentSequencesAndOriginalRepairEnvelopes() throws Exception {
        ChatModels.SessionRecord session = sessions.create(USER, "Concurrent");
        sessions.send(USER, session.id(), Ids.next(), text("First"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            ArrayList<Future<ChatModels.InputAcceptanceResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int number = i;
                futures.add(pool.submit(() -> sessions.send(USER, session.id(), Ids.next(), text("Queued " + number))));
            }
            for (Future<ChatModels.InputAcceptanceResponse> future : futures) {
                assertEquals("USER", future.get(15, TimeUnit.SECONDS).item().source());
            }
        } finally {
            pool.shutdownNow();
        }
        List<CommandPayload.InputAccepted> repaired = sessions.repairInputs(USER, session.id(), 0, 13, 100);
        assertEquals(13, repaired.size());
        for (int index = 0; index < repaired.size(); index++) {
            CommandPayload.InputAccepted input = repaired.get(index);
            assertEquals(index + 1, input.item().inputSequenceNo());
            assertEquals(index + 1, input.item().sequenceNo());
            assertEquals(input.eventId(), input.item().id());
            String original = jdbc.sql("SELECT envelope FROM outbox_event WHERE event_id=?")
                    .param(input.eventId()).query(String.class).single();
            assertEquals(json.hash(json.read(original)), json.hash(input));
            ChatModels.ItemProjection item = chats.item(USER, session.id(), input.item().id());
            assertNull(item.runId());
            assertNull(item.turnId());
        }
        assertEquals(0, chats.runs(USER, session.id()).size());
        assertEquals(13, chats.lastEventSequence(USER, session.id()));
    }

    @Test
    void duplicateHttpStopAndContinueDoNotCreateItemsOrSelectAnExecution() throws Exception {
        ChatModels.SessionRecord session = sessions.create(USER, "Controls");
        sessions.send(USER, session.id(), Ids.next(), text("Pending"));
        for (String control : List.of("stop", "continue")) {
            String key = Ids.next();
            String path = "/api/v1/users/" + USER + "/sessions/" + session.id() + "/" + control;
            String first = mvc.perform(post(path).header("Idempotency-Key", key))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            String duplicate = mvc.perform(post(path).header("Idempotency-Key", key))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            assertEquals(json.read(first), json.read(duplicate));
        }
        assertEquals(1, chats.itemsBefore(USER, session.id(), Long.MAX_VALUE, 100).size());
        assertEquals(0, chats.runs(USER, session.id()).size());
        assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE session_id=?")
                .param(session.id()).query(Integer.class).single());
    }

    @Test
    void outOfOrderFinalOutputIsVisibleAndLateDeltasCannotReopenIt() {
        ChatModels.SessionRecord session = sessions.create(USER, "Ordered events");
        sessions.send(USER, session.id(), Ids.next(), text("Hello"));
        IncomingEvent.RunIdentity run = new IncomingEvent.RunIdentity(Ids.next(), 1);
        IncomingEvent.TurnIdentity turn = new IncomingEvent.TurnIdentity(Ids.next(), 1, run);
        IncomingEvent.AgentItemIdentity item = new IncomingEvent.AgentItemIdentity(Ids.next(), 1, turn);
        Instant now = Timestamps.now();
        ObjectNode end = event(session.id(), 5, "RUN_SNAPSHOT",
                new IncomingEvent.RunSnapshot(run, 2, "COMPLETED", now, now, item, null, null));
        receive(end);
        assertEquals("COMPLETED", chats.run(USER, run.id()).status());
        ObjectNode complete = event(session.id(), 4, "MODEL_RESPONSE_COMPLETED",
                new IncomingEvent.ModelResponseCompleted(Ids.next(), turn, List.of(
                        new IncomingEvent.AgentItemSnapshot(item, "AGENT", 2, "MESSAGE", "COMPLETED",
                                new ConversationContent.OrderedContent(text("Final")), now, null, null))));
        receive(complete);
        receive(complete);
        receive(event(session.id(), 3, "ITEM_TEXT_DELTA", new IncomingEvent.ItemTextDelta(item, 1, "old partial")));
        receive(event(session.id(), 2, "AGENT_ITEM_SNAPSHOT",
                new IncomingEvent.AgentItemSnapshot(item, "AGENT", 1, "MESSAGE", "PARTIAL",
                        new ConversationContent.OrderedContent(List.of()), null, null, null)));
        assertEquals(new ConversationContent.OrderedContent(text("Final")), chats.item(USER, session.id(), item.id()).payload());
        assertEquals(4, chats.executionEvents(USER, session.id(), 0, 100).size());
        long originalPosition = chats.item(USER, session.id(), item.id()).sequenceNo();
        ChatModels.AcceptedInputItem steer = sessions.send(USER, session.id(), Ids.next(), text("Steer")).item();
        IncomingEvent.TurnIdentity secondTurn = new IncomingEvent.TurnIdentity(Ids.next(), 2, run);
        IncomingEvent.AgentItemIdentity second = new IncomingEvent.AgentItemIdentity(Ids.next(), 1, secondTurn);
        receive(event(session.id(), 6, "AGENT_ITEM_SNAPSHOT",
                new IncomingEvent.AgentItemSnapshot(second, "AGENT", 1, "MESSAGE", "COMPLETED",
                        new ConversationContent.OrderedContent(text("After steer")), now, null, null)));
        assertTrue(originalPosition < steer.sequenceNo());
        assertTrue(steer.sequenceNo() < chats.item(USER, session.id(), second.id()).sequenceNo());
        assertEquals(run.id(), chats.item(USER, session.id(), second.id()).runId());
    }

    @Test
    void privateResourcesAndEventSubscriptionsRejectAnotherOwner() throws Exception {
        ChatModels.SessionRecord session = sessions.create(USER, "Private");
        ChatModels.AcceptedInputItem input = sessions.send(USER, session.id(), Ids.next(), text("Hello")).item();
        IncomingEvent.RunIdentity run = new IncomingEvent.RunIdentity(Ids.next(), 1);
        Instant now = Timestamps.now();
        receive(event(session.id(), 1, "RUN_SNAPSHOT", new IncomingEvent.RunSnapshot(run, 1, "RUNNING", now, null, null, null, null)));
        for (String route : List.of("/sessions/" + session.id(), "/sessions/" + session.id() + "/items",
                "/sessions/" + session.id() + "/items/" + input.id(), "/runs/" + run.id(),
                "/sessions/" + session.id() + "/execution-events", "/sessions/" + session.id() + "/events")) {
            mvc.perform(get("/api/v1/users/" + OTHER + route)).andExpect(status().isNotFound());
        }
        ObjectNode wrong = event(session.id(), 2, "RUN_SNAPSHOT",
                new IncomingEvent.RunSnapshot(run, 2, "FAILED", now, now, null, "FAILED", "failure")).put("user_id", OTHER);
        receive(wrong);
        assertEquals("RUNNING", chats.run(USER, run.id()).status());
        assertEquals("REJECTED", jdbc.sql("SELECT status FROM inbox_event WHERE event_id=?")
                .param(wrong.path("event_id").asText()).query(String.class).single());
    }

    @Test
    void persistedEventsReplayAfterTheLastDeliveredCursor() throws Exception {
        ChatModels.SessionRecord session = sessions.create(USER, "Replay");
        sessions.send(USER, session.id(), Ids.next(), text("One"));
        long cursor = sessions.projectionPage(USER, session.id(), null, 50).lastEventSequenceNo();
        sessions.send(USER, session.id(), Ids.next(), text("Two"));
        List<ChatModels.SessionEvent> expected = chats.events(USER, session.id(), cursor, 100);
        assertFalse(expected.isEmpty());
        MvcResult result = mvc.perform(get("/api/v1/users/" + USER + "/sessions/" + session.id() + "/events")
                        .header("Last-Event-ID", cursor)).andExpect(request().asyncStarted()).andReturn();
        await(() -> new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("id:" + expected.get(0).sequenceNo()));
        assertFalse(result.getResponse().getContentAsString().contains("id:" + cursor + "\n"));
        result.getRequest().getAsyncContext().complete();
    }

    @Test
    void kafkaContractDoublesCoverTaskReceiptSynchronousImportAndFreshTaskNotificationRun() throws Exception {
        HttpServer files = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6R6sAAAAASUVORK5CYII=");
        files.createContext("/result", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) { body.write(bytes); }
        });
        files.start();
        ChatModels.SessionRecord session = sessions.create(USER, "Contract workflow");
        try (FakePythonConsumers consumers = new FakePythonConsumers(KAFKA.getBootstrapServers(), json, kafka, mvc,
                "http://127.0.0.1:" + files.getAddress().getPort() + "/result", session.id())) {
            consumers.start();
            sessions.send(USER, session.id(), Ids.next(), text("Create an image"));
            await(() -> {
                publisher.publishPending();
                return consumers.taskId() != null && tasks.view(USER, consumers.taskId()).status().equals("SUCCEEDED");
            });
            await(() -> {
                publisher.publishPending();
                return consumers.sawSuccess() && chats.runs(USER, session.id()).size() == 2
                        && chats.runs(USER, session.id()).stream().allMatch(run -> "COMPLETED".equals(run.status()));
            });
            List<ChatModels.ItemProjection> inputs = chats.itemsBefore(USER, session.id(), Long.MAX_VALUE, 100)
                    .stream().filter(item -> !"AGENT".equals(item.source())).toList();
            assertEquals(2, inputs.size());
            ChatModels.ItemProjection notification = inputs.stream().filter(item -> "TASK_EVENT".equals(item.kind())).findFirst().orElseThrow();
            assertNull(notification.runId());
            assertNull(notification.turnId());
            assertEquals(new ConversationContent.TaskEventContent(consumers.taskId()), notification.payload());
            String asset = jdbc.sql("SELECT id FROM asset WHERE task_id=?").param(consumers.taskId()).query(String.class).single();
            assertEquals(List.of(new ConversationContent.TaskAssetReference(asset, 0)), tasks.snapshot(USER, session.id(), consumers.taskId()).assets());
            mvc.perform(get("/api/v1/users/" + USER + "/assets/" + asset + "/content").header("Range", "bytes=0-3"))
                    .andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOf(bytes, 4)));
            mvc.perform(get("/api/v1/users/" + OTHER + "/assets/" + asset + "/content")).andExpect(status().isNotFound());
            assertThrows(ApiException.class, () -> sessions.send(OTHER, sessions.create(OTHER, "Other").id(), Ids.next(),
                    List.of(new ContentBlock.Asset(asset))));
            consumers.assertHealthy();
        } finally {
            files.stop(0);
        }
    }

    private List<ContentBlock> text(String value) { return List.of(new ContentBlock.Text(value)); }

    private ObjectNode event(String session, long sequence, String type, IncomingEvent.ExecutionBody body) {
        ObjectNode envelope = json.object().put("schema_version", 2).put("event_id", Ids.next()).put("event_type", type)
                .put("user_id", USER).put("session_id", session).put("event_seq", sequence).put("occurred_at", Timestamps.now().toString());
        envelope.set("body", json.tree(body));
        return envelope;
    }

    private void receive(JsonNode event) {
        inbox.receive(Topics.AGENT_EVENTS, event.path("user_id").asText() + ":" + event.path("session_id").asText(), json.write(event), 0, 0);
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for workflow state");
            Thread.sleep(50);
        }
    }
}
