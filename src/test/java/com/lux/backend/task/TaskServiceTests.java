package com.lux.backend.task;

import com.lux.backend.asset.AssetRecord;
import com.lux.backend.asset.AssetRepository;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.chat.ConversationContent;
import com.lux.backend.chat.SessionService;
import com.lux.backend.common.ApiException;
import com.lux.backend.infrastructure.CommandPayload;
import com.lux.backend.infrastructure.IncomingEvent;
import com.lux.backend.infrastructure.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static com.lux.backend.task.TaskTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskServiceTests {
    private final TaskRepository repository = mock(TaskRepository.class);
    private final ChatRepository chats = mock(ChatRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final SessionService sessions = mock(SessionService.class);
    private final TaskService service = new TaskService(repository, chats, assets, outbox, sessions, transactions());

    @Test void sessionOnlySubmissionDoesNotRequireRunOrCall() {
        ConversationContent.TaskSubmissionReceipt receipt = service.createSessionTask(OWNER,
                new TaskService.CreateSessionTaskRequest(OPERATION, SESSION, "IMAGE", request("{}")));
        assertTrue(receipt.accepted());
        ArgumentCaptor<TaskRecord> record = ArgumentCaptor.forClass(TaskRecord.class);
        verify(repository).insert(record.capture());
        assertEquals(SESSION, record.getValue().sessionId());
        assertEquals(receipt.taskId(), record.getValue().id());
        verify(chats).sessionForMutation(OWNER, SESSION);
        verifyNoMoreInteractions(chats);
        ArgumentCaptor<CommandPayload.Envelope> command = ArgumentCaptor.forClass(CommandPayload.Envelope.class);
        verify(outbox).enqueueEnvelope(anyString(), eq(OWNER + ":" + receipt.taskId()), command.capture());
        CommandPayload.GenerationSubmit submit = (CommandPayload.GenerationSubmit) command.getValue();
        assertEquals(2, submit.schemaVersion());
        assertEquals(OPERATION, submit.operationId());
        assertEquals(receipt.taskId(), submit.taskId());
    }

    @Test void repeatedRequestIgnoresObjectKeyOrderButPreservesOriginalReceipt() {
        TaskRecord original = new TaskRecord(TASK, OWNER, SESSION, OPERATION, "IMAGE", request("{\"a\":1,\"b\":[2,3]}"),
                "FAILED", 9, 3, null, "GENERATION", "ERROR", "original", java.time.Instant.EPOCH,
                java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        when(repository.findByOperation(OWNER, OPERATION)).thenReturn(Optional.of(original));
        assertEquals(new ConversationContent.TaskSubmissionReceipt(TASK, true), service.createSessionTask(OWNER,
                new TaskService.CreateSessionTaskRequest(OPERATION, SESSION, "IMAGE", request("{\"b\":[2,3],\"a\":1}"))));
        assertEquals(TASK, service.createSessionTask(OWNER, new TaskService.CreateSessionTaskRequest(OPERATION,
                SESSION.toUpperCase(java.util.Locale.ROOT), "IMAGE", request("{\"b\":[2,3],\"a\":1}"))).taskId());
        verifyNoInteractions(outbox, sessions, chats);
        assertThrows(ApiException.class, () -> service.createSessionTask(OWNER,
                new TaskService.CreateSessionTaskRequest(OPERATION, SESSION, "IMAGE", request("{\"a\":1,\"b\":[3,2]}"))));
    }

    @Test void uniquenessRaceComparesWinningTaskAfterRollback() {
        when(repository.findByOperation(OWNER, OPERATION)).thenReturn(Optional.empty(), Optional.empty(), Optional.of(task("CREATED", 0, null)));
        doThrow(new DataIntegrityViolationException("operation uniqueness")).when(repository).insert(any());
        assertEquals(TASK, service.createSessionTask(OWNER,
                new TaskService.CreateSessionTaskRequest(OPERATION, SESSION, "IMAGE", request("{}"))).taskId());
        verifyNoInteractions(outbox);
    }

    @Test void sourceManifestAtAlreadyAcceptedSequenceResumesImportWithoutApplyingInbox() {
        List<ResultSource> sources = List.of(new ResultSource("https://example.test/result", "a.png", "image/png"));
        TaskRecord current = task("IMPORTING", 4, sources);
        when(repository.getForMutation(OWNER, TASK)).thenReturn(current);
        when(repository.get(OWNER, TASK)).thenReturn(current);
        AtomicInteger applied = new AtomicInteger();
        TaskService.ImportPlan plan = service.admitGeneration(success(4, sources), applied::incrementAndGet);
        assertNotNull(plan);
        assertEquals(sources, plan.sources());
        assertEquals(0, applied.get());
        verify(sessions, never()).acceptTaskTerminal(anyString(), anyString(), anyString());
    }

    @Test void terminalDuplicateDoesNotNotifyOrReopenTask() {
        List<ResultSource> sources = List.of(new ResultSource("https://example.test/result", "a.png", "image/png"));
        when(repository.getForMutation(OWNER, TASK)).thenReturn(task("FAILED", 4, sources));
        AtomicInteger applied = new AtomicInteger();
        assertNull(service.admitGeneration(success(4, sources), applied::incrementAndGet));
        assertEquals(1, applied.get());
        verifyNoInteractions(sessions);
        verify(repository, never()).observed(anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test void acceptedMeansConnectorAdmissionAndOnlyInvalidatesFrontend() {
        when(repository.getForMutation(OWNER, TASK)).thenReturn(task("CREATED", 0, null));
        AtomicInteger applied = new AtomicInteger();
        IncomingEvent.Generation event = new IncomingEvent.Generation(header(1), IncomingEvent.ConnectorExecutionStatus.ACCEPTED, null, false);
        assertNull(service.admitGeneration(event, applied::incrementAndGet));
        verify(repository).observed(OWNER, TASK, "SUBMITTED", 1, null);
        verify(sessions).publishTaskInvalidation(OWNER, SESSION, TASK);
        verify(sessions, never()).acceptTaskTerminal(anyString(), anyString(), anyString());
        assertEquals(1, applied.get());
    }

    @Test void confirmedGenerationFailureAndInboxMarkerShareTheTransaction() {
        when(repository.getForMutation(OWNER, TASK)).thenReturn(task("RUNNING", 1, null));
        when(repository.finish(OWNER, TASK, "FAILED", "GENERATION", "PROVIDER_DENIED", "original error")).thenReturn(true);
        IncomingEvent.Generation event = new IncomingEvent.Generation(header(2), IncomingEvent.ConnectorExecutionStatus.FAILED,
                new IncomingEvent.GenerationFailureOutcome("FAILED", new IncomingEvent.GenerationError("PROVIDER_DENIED", "PROVIDER", "original error", null)), false);
        transactions().executeWithoutResult(status -> service.admitGeneration(event, () -> assertTrue(TransactionSynchronizationManager.isActualTransactionActive())));
        verify(sessions).acceptTaskTerminal(OWNER, SESSION, TASK);
        verify(repository).finish(OWNER, TASK, "FAILED", "GENERATION", "PROVIDER_DENIED", "original error");
    }

    @Test void incompleteFinalVerificationThrowsRatherThanSignalingConsumptionSuccess() {
        List<ResultSource> sources = List.of(new ResultSource("https://example.test/result", "a.png", "image/png"));
        TaskRecord current = task("IMPORTING", 2, sources);
        when(repository.getForMutation(OWNER, TASK)).thenReturn(current);
        assertThrows(IllegalStateException.class, () -> service.completeImport(new TaskService.ImportPlan(current, sources), List.of()));
        verifyNoInteractions(sessions);
    }

    @Test void newlyVerifiedPartialAssetInvalidatesFrontendButDoesNotWakeAgent() {
        List<ResultSource> sources = List.of(new ResultSource("https://example.test/result", "a.png", "image/png"));
        TaskRecord current = task("IMPORTING", 2, sources);
        when(repository.getForMutation(OWNER, TASK)).thenReturn(current);
        AssetRecord proposed = new AssetRecord(TaskResultService.sourceId(TASK, 0), OWNER, TASK,
                "a.png", "image/png", 1, "digest", java.time.Instant.EPOCH);
        TaskService.ImportPlan plan = new TaskService.ImportPlan(current, sources);
        assertEquals(proposed, service.registerImportAsset(plan, proposed, 0));
        verify(sessions).publishTaskInvalidation(OWNER, SESSION, TASK);
        verify(sessions, never()).acceptTaskTerminal(anyString(), anyString(), anyString());
        when(assets.find(OWNER, proposed.id())).thenReturn(Optional.of(proposed));
        service.registerImportAsset(plan, proposed, 0);
        verify(repository, times(1)).assetRegistered(OWNER, TASK);
        verify(sessions, times(1)).publishTaskInvalidation(OWNER, SESSION, TASK);
    }

    @Test void snapshotUsesTrueSourceIndexesAndRejectsOtherSession() {
        when(repository.get(OWNER, TASK)).thenReturn(task("FAILED", 4, null));
        when(assets.taskReferences(OWNER, TASK)).thenReturn(List.of(new ConversationContent.TaskAssetReference(UUID.randomUUID().toString(), 2)));
        ConversationContent.TaskQuerySnapshot snapshot = service.snapshot(OWNER, SESSION, TASK);
        assertEquals(2, snapshot.assets().get(0).sourceIndex());
        assertEquals("IMPORT", snapshot.failure().stage());
        assertThrows(ApiException.class, () -> service.snapshot(OWNER, UUID.randomUUID().toString(), TASK));
    }

    @Test void unchangedFirstPageCannotStarveLaterPendingTasks() {
        List<TaskRecord> firstPage = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            firstPage.add(new TaskRecord(UUID.randomUUID().toString(), OWNER, SESSION, UUID.randomUUID().toString(),
                    "IMAGE", request("{}"), "RUNNING", 2, 1, null, null, null, null, null,
                    java.time.Instant.EPOCH, java.time.Instant.EPOCH));
        }
        TaskRecord later = new TaskRecord(UUID.randomUUID().toString(), OWNER, SESSION, UUID.randomUUID().toString(),
                "IMAGE", request("{}"), "RUNNING", 2, 1, null, null, null, null, null,
                java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(1));
        firstPage.sort(java.util.Comparator.comparing(TaskRecord::id));
        TaskRecord pageEnd = firstPage.get(firstPage.size() - 1);
        when(repository.staleTasks((TaskRepository.ObservationCursor) null)).thenReturn(firstPage);
        when(repository.staleTasks(new TaskRepository.ObservationCursor(pageEnd.updatedAt(), pageEnd.id())))
                .thenReturn(List.of(later));
        when(repository.staleTasks(new TaskRepository.ObservationCursor(later.updatedAt(), later.id())))
                .thenReturn(List.of());
        when(repository.getForMutation(eq(OWNER), anyString())).thenAnswer(call -> {
            String id = call.getArgument(1);
            return firstPage.stream().filter(task -> task.id().equals(id)).findFirst().orElse(later);
        });
        service.reconcileStale();
        service.reconcileStale();
        verify(outbox).enqueueEnvelope(anyString(), eq(OWNER + ":" + later.id()), any());
        service.reconcileStale();
        verify(repository, times(2)).staleTasks((TaskRepository.ObservationCursor) null);
        verify(outbox, times(2)).enqueueEnvelope(anyString(), eq(OWNER + ":" + firstPage.get(0).id()), any());
    }
}
