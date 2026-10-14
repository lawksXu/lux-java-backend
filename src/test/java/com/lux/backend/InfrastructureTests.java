package com.lux.backend;

import com.lux.backend.chat.RunEventService;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.infrastructure.*;
import com.lux.backend.task.TaskResultService;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InfrastructureTests {
    @Test void brokerFailureRetainsTheSameOutboxIdentityForRetry() {
        OutboxRepository repository=mock(OutboxRepository.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        OutboxRepository.Pending event=new OutboxRepository.Pending(1,Topics.AGENT_COMMANDS,"owner:session","{\"event_id\":\"stable\"}",0);
        when(repository.pending()).thenReturn(List.of(event));
        when(kafka.send(event.topic(),event.partitionKey(),event.envelope())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        OutboxPublisher publisher=new OutboxPublisher(repository,kafka);
        publisher.publishPending();
        verify(repository,never()).delivered(anyLong());
        verify(repository).failed(eq(event),contains("offline"));
        when(kafka.send(event.topic(),event.partitionKey(),event.envelope())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publishPending();
        verify(repository).delivered(1);
        verify(kafka,times(2)).send(event.topic(),event.partitionKey(),event.envelope());
    }

    @Test void malformedTimeIsRejectedInsteadOfRetriedForever() {
        JsonCodec json=new JsonCodec(JsonMapper.builder().build());
        InboxRepository inbox=mock(InboxRepository.class);
        TransactionTemplate transactions = transactions();
        String id="00000000-0000-0000-0000-000000000001";
        ObjectNode event=json.object().put("event_id",id).put("schema_version",2).put("user_id",id)
                .put("session_id",id).put("event_seq",1).put("occurred_at","invalid");
        event.put("event_type", "ITEM_TEXT_DELTA");
        event.set("body", deltaBody(json, id, 12));
        when(inbox.forProcessing(Topics.AGENT_EVENTS,id)).thenReturn(new InboxRepository.Entry(Topics.AGENT_EVENTS,id,event,"RECEIVED",0));
        RunEventService runs=mock(RunEventService.class);
        new InboxProcessor(inbox,runs,mock(TaskResultService.class),transactions,json).process(Topics.AGENT_EVENTS,id);
        verify(inbox).rejected(eq(Topics.AGENT_EVENTS),eq(id),anyString());
        verify(inbox,never()).retry(anyString(),anyString(),anyString());
        verifyNoInteractions(runs);
    }

    @Test void malformedKnownPayloadIsRejectedPermanentlyBeforeCallingBusinessService() {
        JsonCodec json = new JsonCodec(JsonMapper.builder().build());
        InboxRepository inbox = mock(InboxRepository.class);
        TransactionTemplate transactions = transactions();
        String id = "00000000-0000-0000-0000-000000000001";
        ObjectNode event = json.object().put("event_id", id).put("schema_version", 2).put("user_id", id)
            .put("session_id", id).put("event_seq", 1)
            .put("occurred_at", "2026-09-26T00:00:00Z").put("event_type", "ITEM_TEXT_DELTA");
        event.set("body", deltaBody(json, id, 12));
        when(inbox.forProcessing(Topics.AGENT_EVENTS, id)).thenReturn(new InboxRepository.Entry(Topics.AGENT_EVENTS, id, event, "RECEIVED", 0));
        RunEventService runs = mock(RunEventService.class);
        new InboxProcessor(inbox, runs, mock(TaskResultService.class), transactions, json).process(Topics.AGENT_EVENTS, id);
        verify(inbox).rejected(eq(Topics.AGENT_EVENTS), eq(id), contains("text"));
        verify(inbox, never()).retry(anyString(), anyString(), anyString());
        verifyNoInteractions(runs);
    }

    @Test void unexpectedInboxSourceIsPermanentlyRejectedBeforeBusinessDispatch() {
        JsonCodec json = new JsonCodec(JsonMapper.builder().build());
        InboxRepository inbox = mock(InboxRepository.class);
        TransactionTemplate transactions = transactions();
        String id = "00000000-0000-0000-0000-000000000001";
        ObjectNode event = json.object().put("event_id", id).put("schema_version", 2).put("user_id", id)
            .put("session_id", id).put("run_id", id).put("task_id", id).put("sequence_no", 1)
            .put("occurred_at", "2026-09-26T00:00:00Z").put("event_type", "TASK_RUNNING");
        event.set("payload", json.object());
        when(inbox.forProcessing("unexpected", id)).thenReturn(new InboxRepository.Entry("unexpected", id, event, "RECEIVED", 0));
        RunEventService runs = mock(RunEventService.class);
        TaskResultService tasks = mock(TaskResultService.class);
        new InboxProcessor(inbox, runs, tasks, transactions, json).process("unexpected", id);
        verify(inbox).rejected(eq("unexpected"), eq(id), contains("source"));
        verify(inbox, never()).retry(anyString(), anyString(), anyString());
        verifyNoInteractions(runs, tasks);
    }

    @Test void invalidPartitionKeyIsPersistedRejectedWithoutBeingReceivable() {
        JsonCodec json=new JsonCodec(JsonMapper.builder().build());
        InboxRepository inbox=mock(InboxRepository.class);
        InboxProcessor processor=new InboxProcessor(inbox,mock(RunEventService.class),mock(TaskResultService.class),
                transactions(),json);
        String id="00000000-0000-0000-0000-000000000001";
        String raw="{\"event_id\":\""+id+"\",\"user_id\":\""+id+"\",\"session_id\":\""+id+"\"}";

        processor.receive(Topics.AGENT_EVENTS,"wrong-key",raw,2,9);

        verify(inbox).receiveRejected(eq(Topics.AGENT_EVENTS),argThat(diagnostic -> !diagnostic.equals(id)),
                eq(id),any(),contains("Partition key"));
        verify(inbox,never()).receive(anyString(),anyString(),anyString(),any());
        verify(inbox,never()).rejected(anyString(),anyString(),anyString());
    }

    @Test void wrongKeyDuplicateDoesNotRejectThePreviouslyAcceptedEvent() {
        JsonCodec json=new JsonCodec(JsonMapper.builder().build());
        InboxRepository inbox=mock(InboxRepository.class);
        String id="00000000-0000-0000-0000-000000000001";
        String raw="{\"event_id\":\""+id+"\",\"user_id\":\""+id+"\",\"session_id\":\""+id+"\"}";
        when(inbox.receive(eq(Topics.AGENT_EVENTS),eq(id),eq(id),any())).thenReturn(true);
        when(inbox.forProcessing(Topics.AGENT_EVENTS, id)).thenReturn(new InboxRepository.Entry(
                Topics.AGENT_EVENTS, id, json.read(raw), "APPLIED", 0));
        InboxProcessor processor=new InboxProcessor(inbox,mock(RunEventService.class),mock(TaskResultService.class),
                transactions(),json);

        processor.receive(Topics.AGENT_EVENTS,id+":"+id,raw,2,9);
        processor.receive(Topics.AGENT_EVENTS,"wrong-key",raw,2,10);

        verify(inbox,times(1)).receive(eq(Topics.AGENT_EVENTS),eq(id),eq(id),any());
        verify(inbox).receiveRejected(eq(Topics.AGENT_EVENTS),argThat(diagnostic -> !diagnostic.equals(id)),
                eq(id),any(),contains("Partition key"));
        verify(inbox,never()).rejected(eq(Topics.AGENT_EVENTS),eq(id),anyString());
    }

    @Test void generationDownloadRunsOutsideTheInboxReadTransactionAndCannotSwallowTechnicalFailure() {
        JsonCodec json = new JsonCodec(JsonMapper.builder().build());
        InboxRepository inbox = mock(InboxRepository.class);
        TaskResultService results = mock(TaskResultService.class);
        String id = "00000000-0000-0000-0000-000000000001";
        ObjectNode event = generation(json, id);
        when(inbox.forProcessing(Topics.GENERATION_EVENTS, id)).thenReturn(
                new InboxRepository.Entry(Topics.GENERATION_EVENTS, id, event, "RECEIVED", 0));
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("Ambiguous object publication");
        }).when(results).receive(any(), any());
        InboxProcessor processor = new InboxProcessor(inbox, mock(RunEventService.class), results, transactions(), json);
        assertThrows(IllegalStateException.class, () -> processor.process(Topics.GENERATION_EVENTS, id));
        verify(inbox).retry(eq(Topics.GENERATION_EVENTS), eq(id), contains("Ambiguous"));
        verify(inbox, never()).applied(anyString(), anyString());
        verify(inbox, never()).rejected(anyString(), anyString(), anyString());
    }

    @Test void completedGenerationAppliesOriginalInboxIdentityBeforeListenerReturns() {
        JsonCodec json = new JsonCodec(JsonMapper.builder().build());
        InboxRepository inbox = mock(InboxRepository.class);
        TaskResultService results = mock(TaskResultService.class);
        String id = "00000000-0000-0000-0000-000000000001";
        when(inbox.receive(eq(Topics.GENERATION_EVENTS), eq(id), eq(id), any())).thenReturn(true);
        when(inbox.forProcessing(Topics.GENERATION_EVENTS, id)).thenReturn(
                new InboxRepository.Entry(Topics.GENERATION_EVENTS, id, generation(json, id), "RECEIVED", 0));
        TransactionTemplate transactions = transactions();
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            transactions.executeWithoutResult(status -> call.getArgument(1, Runnable.class).run());
            return null;
        }).when(results).receive(any(), any());
        doAnswer(call -> { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return null; })
                .when(inbox).applied(Topics.GENERATION_EVENTS, id);
        InboxProcessor processor = new InboxProcessor(inbox, mock(RunEventService.class), results, transactions, json);
        new KafkaInbound(processor).receiveGeneration(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                Topics.GENERATION_EVENTS, 0, 3, id + ":" + id, json.write(generation(json, id))));
        verify(inbox).applied(Topics.GENERATION_EVENTS, id);
        verify(inbox, never()).retry(anyString(), anyString(), anyString());
    }

    @Test void genericInboxDrainDoesNotCreateASecondGenerationImporter() {
        InboxRepository inbox = mock(InboxRepository.class);
        TaskResultService results = mock(TaskResultService.class);
        when(inbox.pending()).thenReturn(List.of(new InboxRepository.Key(Topics.GENERATION_EVENTS, "new"),
                new InboxRepository.Key(Topics.LEGACY_GENERATION_EVENTS, "legacy")));
        new InboxProcessor(inbox, mock(RunEventService.class), results, transactions(),
                new JsonCodec(JsonMapper.builder().build())).drain();
        verify(inbox, never()).forProcessing(anyString(), anyString());
        verifyNoInteractions(results);
    }

    private static ObjectNode generation(JsonCodec json, String id) {
        return json.object().put("event_id", id).put("schema_version", 2).put("event_type", "GENERATION_EVENT")
                .put("user_id", id).put("session_id", id).put("task_id", id).put("sequence_no", 1)
                .put("occurred_at", "2026-10-12T00:00:00Z").put("connector_status", "RUNNING").putNull("outcome");
    }

    private static ObjectNode deltaBody(JsonCodec json, String id, int invalidText) {
        ObjectNode run = json.object().put("id", id).put("run_index", 1);
        ObjectNode turn = json.object().put("id", id).put("turn_index", 1);
        turn.set("run", run);
        ObjectNode item = json.object().put("id", id).put("item_index", 1);
        item.set("turn", turn);
        ObjectNode body = json.object().put("stream_seq", 1).put("text", invalidText);
        body.set("item", item);
        return body;
    }

    private static TransactionTemplate transactions() {
        return new TransactionTemplate() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                try { return action.doInTransaction(new SimpleTransactionStatus()); }
                finally { TransactionSynchronizationManager.setActualTransactionActive(previous); }
            }
        };
    }

}
