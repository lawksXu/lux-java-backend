package com.lux.backend;

import com.lux.backend.chat.ContentBlock;
import com.lux.backend.chat.ConversationContent;
import com.lux.backend.common.Ids;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.common.Timestamps;
import com.lux.backend.infrastructure.IncomingEvent;
import com.lux.backend.infrastructure.Topics;
import com.lux.backend.task.ConnectorRequest;
import com.lux.backend.task.ResultSource;
import com.lux.backend.task.TaskService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Kafka/HTTP contract doubles; real model execution and native checkpointing are tested in Python. */
final class FakePythonConsumers implements AutoCloseable {
    private final JsonCodec json;
    private final KafkaTemplate<String, String> kafka;
    private final MockMvc mvc;
    private final String resultUrl;
    private final String session;
    private final KafkaConsumer<String, String> agent;
    private final KafkaConsumer<String, String> connector;
    private final ExecutorService threads = Executors.newFixedThreadPool(2);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CountDownLatch ready = new CountDownLatch(2);
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile boolean succeeded;
    private volatile String task;
    private long eventSequence;
    private long runIndex;

    private record Fact(int schemaVersion, String eventId, String eventType, String occurredAt,
                        String userId, String sessionId, long eventSeq, IncomingEvent.ExecutionBody body) {}
    private record Generation(int schemaVersion, String eventId, String occurredAt, String userId,
                              String sessionId, String taskId, long sequenceNo, String connectorStatus,
                              IncomingEvent.GenerationOutcome outcome) {}

    FakePythonConsumers(String broker, JsonCodec json, KafkaTemplate<String, String> kafka, MockMvc mvc,
                        String resultUrl, String session) {
        this.json = json;
        this.kafka = kafka;
        this.mvc = mvc;
        this.resultUrl = resultUrl;
        this.session = session;
        this.agent = consumer(broker);
        this.connector = consumer(broker);
    }

    void start() throws InterruptedException {
        threads.submit(() -> consume(agent, Topics.AGENT_COMMANDS));
        threads.submit(() -> consume(connector, Topics.GENERATION_COMMANDS));
        if (!ready.await(20, TimeUnit.SECONDS)) throw new AssertionError("Contract consumers did not start", failure.get());
    }

    String taskId() { assertHealthy(); return task; }
    boolean sawSuccess() { assertHealthy(); return succeeded; }
    void assertHealthy() {
        if (failure.get() != null) throw new AssertionError("Contract service failed", failure.get());
    }

    private void consume(KafkaConsumer<String, String> consumer, String topic) {
        try {
            consumer.subscribe(List.of(topic));
            boolean assigned = false;
            while (!closed) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                if (!assigned && !consumer.assignment().isEmpty()) {
                    assigned = true;
                    ready.countDown();
                }
                for (ConsumerRecord<String, String> record : records) {
                    JsonNode event = json.read(record.value());
                    if (!session.equals(event.path("session_id").asText()) || !seen.add(event.path("event_id").asText())) continue;
                    if (topic.equals(Topics.AGENT_COMMANDS)) agentCommand(event); else connectorCommand(event);
                }
            }
        } catch (WakeupException stopped) {
            if (!closed) failure.compareAndSet(null, stopped);
        } catch (Throwable problem) {
            failure.compareAndSet(null, problem);
        } finally {
            consumer.close(Duration.ofSeconds(3));
        }
    }

    private void agentCommand(JsonNode command) throws Exception {
        if (!"INPUT_ACCEPTED".equals(command.path("event_type").asText())) return;
        String owner = command.path("user_id").asText();
        boolean terminalInput = "TASK_EVENT".equals(command.path("item").path("kind").asText());
        IncomingEvent.RunIdentity run = new IncomingEvent.RunIdentity(Ids.next(), ++runIndex);
        IncomingEvent.TurnIdentity first = new IncomingEvent.TurnIdentity(Ids.next(), 1, run);
        IncomingEvent.AgentItemIdentity call = new IncomingEvent.AgentItemIdentity(Ids.next(), 1, first);
        Instant started = Timestamps.now();
        emit(owner, "RUN_SNAPSHOT", new IncomingEvent.RunSnapshot(run, 1, "RUNNING", started, null, null, null, null));
        emit(owner, "TURN_SNAPSHOT", new IncomingEvent.TurnSnapshot(first, 1, "RUNNING", started, null, null, null));
        ConversationContent.ToolCallContent arguments;
        String toolName;
        if (terminalInput) {
            JsonNode content = command.path("item").path("content");
            if (content.size() != 1 || !task.equals(content.path("task_id").asText())) {
                throw new AssertionError("Task notification must contain only the original Task ID");
            }
            toolName = "get_task";
            arguments = new ConversationContent.GetTaskCallContent(new ConversationContent.GetTaskArguments(task));
        } else {
            toolName = "submit_generation";
            arguments = new ConversationContent.SubmitGenerationCallContent(new ConversationContent.SubmitGenerationArguments(
                    "IMAGE", ConnectorRequest.decode(json.read("{\"prompt\":\"Contract test\"}"))));
        }
        String callId = "call_" + call.id();
        IncomingEvent.AgentItemSnapshot callSnapshot = new IncomingEvent.AgentItemSnapshot(call, "AGENT", 1,
                "TOOL_CALL", "COMPLETED", arguments, Timestamps.now(), callId, toolName);
        emit(owner, "MODEL_RESPONSE_COMPLETED", new IncomingEvent.ModelResponseCompleted(Ids.next(), first, List.of(callSnapshot)));
        ConversationContent.ToolResultContent outcome;
        if (terminalInput) {
            MockHttpServletResponse response = mvc.perform(get("/internal/v2/users/{owner}/sessions/{session}/tasks/{task}",
                    owner, session, task)).andReturn().getResponse();
            if (response.getStatus() != 200) throw new AssertionError("Task query HTTP " + response.getStatus());
            outcome = (ConversationContent.TaskQuerySnapshot) com.lux.backend.infrastructure.EventProtocol.payload(
                    "TOOL_RESULT", null, json.read(response.getContentAsString()));
            if (!"SUCCEEDED".equals(((ConversationContent.TaskQuerySnapshot) outcome).status())) {
                throw new AssertionError("Terminal notification preceded durable import");
            }
            succeeded = true;
        } else {
            TaskService.CreateSessionTaskRequest request = new TaskService.CreateSessionTaskRequest(call.id(), session,
                    "IMAGE", ((ConversationContent.SubmitGenerationCallContent) arguments).arguments().request());
            MockHttpServletResponse response = mvc.perform(post("/internal/v2/users/{owner}/tasks", owner)
                    .contentType("application/json").content(json.write(request))).andReturn().getResponse();
            if (response.getStatus() != 201) throw new AssertionError("Task HTTP " + response.getStatus());
            outcome = (ConversationContent.TaskSubmissionReceipt) com.lux.backend.infrastructure.EventProtocol.payload(
                    "TOOL_RESULT", null, json.read(response.getContentAsString()));
            task = ((ConversationContent.TaskSubmissionReceipt) outcome).taskId();
        }
        emit(owner, "AGENT_ITEM_SNAPSHOT", new IncomingEvent.AgentItemSnapshot(
                new IncomingEvent.AgentItemIdentity(Ids.next(), 2, first), "AGENT", 1, "TOOL_RESULT", "COMPLETED",
                outcome, Timestamps.now(), callId, null));
        emit(owner, "TURN_SNAPSHOT", new IncomingEvent.TurnSnapshot(first, 2, "COMPLETED", started, Timestamps.now(), null, null));
        IncomingEvent.TurnIdentity last = new IncomingEvent.TurnIdentity(Ids.next(), 2, run);
        Instant lastStarted = Timestamps.now();
        emit(owner, "TURN_SNAPSHOT", new IncomingEvent.TurnSnapshot(last, 1, "RUNNING", lastStarted, null, null, null));
        IncomingEvent.AgentItemIdentity answer = new IncomingEvent.AgentItemIdentity(Ids.next(), 1, last);
        ConversationContent.OrderedContent text = new ConversationContent.OrderedContent(List.of(
                new ContentBlock.Text(terminalInput ? "Your image is ready." : "Your image is being generated.")));
        emit(owner, "MODEL_RESPONSE_COMPLETED", new IncomingEvent.ModelResponseCompleted(Ids.next(), last,
                List.of(new IncomingEvent.AgentItemSnapshot(answer, "AGENT", 1, "MESSAGE", "COMPLETED", text,
                        Timestamps.now(), null, null))));
        emit(owner, "TURN_SNAPSHOT", new IncomingEvent.TurnSnapshot(last, 2, "COMPLETED", lastStarted, Timestamps.now(), null, null));
        emit(owner, "RUN_SNAPSHOT", new IncomingEvent.RunSnapshot(run, 2, "COMPLETED", started,
                Timestamps.now(), answer, null, null));
    }

    private void connectorCommand(JsonNode command) throws Exception {
        if (!"SUBMIT".equals(command.path("event_type").asText())) return;
        String owner = command.path("user_id").asText();
        String id = command.path("task_id").asText();
        Generation event = new Generation(2, Ids.next(), Timestamps.now().toString(), owner, session, id, 1,
                "SUCCEEDED", new IncomingEvent.GenerationSuccessOutcome("SUCCEEDED", List.of(
                        ResultSource.decode(json.read("{\"url\":\"" + resultUrl + "\",\"filename\":\"mock.png\",\"media_type\":\"image/png\"}")))));
        kafka.send(Topics.GENERATION_EVENTS, owner + ":" + id, json.write(event)).get(10, TimeUnit.SECONDS);
    }

    private void emit(String owner, String type, IncomingEvent.ExecutionBody body) throws Exception {
        kafka.send(Topics.AGENT_EVENTS, owner + ":" + session, json.write(new Fact(2, Ids.next(), type,
                Timestamps.now().toString(), owner, session, ++eventSequence, body))).get(10, TimeUnit.SECONDS);
    }

    private KafkaConsumer<String, String> consumer(String broker) {
        return new KafkaConsumer<>(Map.of("bootstrap.servers", broker, "group.id", "lux-fake-" + Ids.next(),
                "auto.offset.reset", "earliest", "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName()));
    }

    @Override
    public void close() throws InterruptedException {
        closed = true;
        agent.wakeup();
        connector.wakeup();
        threads.shutdown();
        if (!threads.awaitTermination(5, TimeUnit.SECONDS)) threads.shutdownNow();
    }
}
