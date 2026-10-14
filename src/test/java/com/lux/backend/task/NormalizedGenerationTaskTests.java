package com.lux.backend.task;

import com.lux.backend.asset.AssetRecord;
import com.lux.backend.asset.AssetRepository;
import com.lux.backend.asset.AssetStore;
import com.lux.backend.asset.LocalAssetStore;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.chat.SessionService;
import com.lux.backend.common.ApiException;
import com.lux.backend.generation.GenerationMediaInspector;
import com.lux.backend.generation.GenerationResultVerifier;
import com.lux.backend.infrastructure.CommandPayload;
import com.lux.backend.infrastructure.OutboxRepository;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.lux.backend.generation.GenerationPlanContract.*;
import static com.lux.backend.task.TaskTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 保护新请求的任务、结果清单和真实导入边界，不访问外部提供者。 */
class NormalizedGenerationTaskTests {
    @TempDir Path directory;
    @Test void legacySubmissionCannotAdmitNewNormalizedGeneration() {
        TaskRepository repository = mock(TaskRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        TaskService service = service(repository, outbox);
        TaskService.CreateSessionTaskRequest request = new TaskService.CreateSessionTaskRequest(
            OPERATION, SESSION, "IMAGE", ConnectorRequest.normalized(imageRequest(9, 7)));
        ApiException rejection = assertThrows(ApiException.class, () -> service.createSessionTask(OWNER, request));
        assertEquals("Normalized generation requires a generation plan", rejection.getMessage());
        verify(repository, never()).insert(any());
        verifyNoInteractions(outbox);
    }
    @Test void planTasksRetainExplicitDistinctIdentitiesAndTypedOutboxRequest() {
        TaskRepository repository = mock(TaskRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        TaskService service = service(repository, outbox);
        UUID plan = UUID.randomUUID();
        UUID firstTask = UUID.randomUUID();
        UUID secondTask = UUID.randomUUID();
        UUID firstOperation = UUID.randomUUID();
        UUID secondOperation = UUID.randomUUID();
        ImageGenerationRequest request = imageRequest(9, 7);
        transactions().executeWithoutResult(status -> {
            service.createPlanTask(OWNER, SESSION, plan, firstTask, firstOperation, request);
            service.createPlanTask(OWNER, SESSION, plan, secondTask, secondOperation, request);
        });
        ArgumentCaptor<TaskRecord> tasks = ArgumentCaptor.forClass(TaskRecord.class);
        verify(repository, times(2)).insert(tasks.capture());
        assertEquals(List.of(firstTask.toString(), secondTask.toString()), tasks.getAllValues().stream().map(TaskRecord::id).toList());
        assertEquals(List.of(firstOperation.toString(), secondOperation.toString()), tasks.getAllValues().stream().map(TaskRecord::operationId).toList());
        assertTrue(tasks.getAllValues().stream().allMatch(task -> task.planId().equals(plan.toString()) && task.payload().normalized().equals(request)));
        ArgumentCaptor<CommandPayload.Envelope> commands = ArgumentCaptor.forClass(CommandPayload.Envelope.class);
        verify(outbox, times(2)).enqueueEnvelope(anyString(), anyString(), commands.capture());
        for (int index = 0; index < 2; index++) {
            CommandPayload.GenerationSubmit command = (CommandPayload.GenerationSubmit) commands.getAllValues().get(index);
            assertEquals(tasks.getAllValues().get(index).id(), command.taskId());
            assertEquals(tasks.getAllValues().get(index).operationId(), command.operationId());
            assertEquals(request, command.request().normalized());
        }
    }
    @Test void normalizedZeroAndMultipleFilesFailBeforeImportAndApplyInbox() {
        for (int count : new int[]{0, 2}) {
            TaskRepository repository = mock(TaskRepository.class);
            TaskService service = service(repository, mock(OutboxRepository.class));
            List<ResultSource> sources = count == 0 ? List.of() : List.of(source("https://example.test/1"), source("https://example.test/2"));
            when(repository.getForMutation(OWNER, TASK)).thenReturn(normalizedTask(imageRequest(9, 7), "RUNNING", null));
            AtomicInteger applied = new AtomicInteger();
            assertNull(service.admitGeneration(success(4, sources), applied::incrementAndGet));
            assertEquals(1, applied.get());
            verify(repository).finish(eq(OWNER), eq(TASK), eq("FAILED"), eq("GENERATION"), eq("OUTPUT_FILE_COUNT_MISMATCH"), anyString());
            verify(repository, never()).get(anyString(), anyString());
        }
    }
    @Test void legacyTwoFilesContinueIntoOriginalImportPath() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskService service = service(repository, mock(OutboxRepository.class));
        List<ResultSource> sources = List.of(source("https://example.test/1"), source("https://example.test/2"));
        TaskRecord legacy = task("RUNNING", 3, null);
        when(repository.getForMutation(OWNER, TASK)).thenReturn(legacy);
        when(repository.get(OWNER, TASK)).thenReturn(legacy);
        AtomicInteger applied = new AtomicInteger();
        assertEquals(sources, service.admitGeneration(success(4, sources), applied::incrementAndGet).sources());
        assertEquals(0, applied.get());
        verify(repository, never()).finish(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }
    @Test void downloadedImageIsInspectedOutsideTransactionAndFactsPersistBeforeCompletion() throws Exception {
        try (Source source = new Source(png())) {
            ImportFixture fixture = new ImportFixture(source.url(), imageRequest(9, 7));
            GenerationMediaInspector inspector = spy(new GenerationMediaInspector(fixture.store, "missing", 1024));
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                AssetStore.TransferDeadline deadline = call.getArgument(1);
                assertTrue(deadline.remaining().toMillis() <= 2000);
                return call.callRealMethod();
            }).when(inspector).inspect(any(), any());
            doAnswer(call -> {
                VerifiedImageMetadata facts = call.getArgument(3);
                assertEquals(new PixelDimensions(9, 7), facts.dimensions());
                assertEquals("image/png", facts.detectedMimeType());
                assertEquals(fixture.record.sha256(), facts.sha256());
                assertEquals(UUID.fromString(fixture.record.id()), facts.assetId());
                fixture.inspections.incrementAndGet();
                return null;
            }).when(fixture.assets).saveInspection(anyString(), anyString(), anyString(), any());
            doAnswer(call -> { assertEquals(1, fixture.inspections.get()); return null; }).when(fixture.tasks).completeImport(any(), anyList());
            AtomicInteger applied = new AtomicInteger();
            fixture.service(inspector).receive(success(4, fixture.sources), applied::incrementAndGet);
            assertEquals(1, source.requests.get());
            assertEquals(1, applied.get());
            verify(fixture.tasks).completeImport(any(), argThat(records -> records.size() == 1));
            verify(fixture.tasks, never()).failImport(any(), anyString(), anyString());
        }
    }
    @Test void confirmedWrongDimensionsFailsOutputVerificationAndAcknowledgesOriginalResult() throws Exception {
        try (Source source = new Source(png())) {
            ImportFixture fixture = new ImportFixture(source.url(), imageRequest(10, 7));
            AtomicInteger applied = new AtomicInteger();
            fixture.service(new GenerationMediaInspector(fixture.store, "missing", 1024)).receive(success(4, fixture.sources), applied::incrementAndGet);
            assertEquals(1, source.requests.get());
            assertEquals(1, applied.get());
            verify(fixture.tasks).failImport(any(), eq("OUTPUT_VERIFICATION_FAILED"), anyString());
            verify(fixture.tasks, never()).completeImport(any(), anyList());
            verify(fixture.assets, never()).saveInspection(anyString(), anyString(), anyString(), any());
        }
    }
    @Test void inspectedVideoOutsideDurationToleranceFailsAtImportBoundary() throws Exception {
        try (Source source = new Source(png())) {
            SizePreset preset = new SizePreset("fixture", new AspectRatio(16, 9), "fixture", new PixelDimensions(16, 9));
            ResolvedPresetSize size = new ResolvedPresetSize("resolved_preset_size", preset);
            VideoGenerationRequest request = new VideoGenerationRequest("video_generation_plan_v1", 1,
                new ModelSelection("fixture", "fixture", 1L), new ProviderBinding("fixture", "fixture", "fixture"),
                new VideoExecutionParameters("video_execution_parameters", "fixture", size, List.of(), VideoOutputFormat.MP4, 4, false, VideoMode.values()[0]),
                new VideoResultExpectations("video_result_expectations", 1, size, VideoOutputFormat.MP4, 4, 100L, false));
            ImportFixture fixture = new ImportFixture(source.url(), request);
            GenerationMediaInspector inspector = mock(GenerationMediaInspector.class);
            when(inspector.inspect(any(), any())).thenAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                AssetRecord asset = call.getArgument(0);
                return new VerifiedVideoMetadata("verified_video_metadata", UUID.fromString(asset.id()), asset.sha256(),
                    asset.sizeBytes(), "video/mp4", new PixelDimensions(16, 9), 4101L, false);
            });
            AtomicInteger applied = new AtomicInteger();
            fixture.service(inspector).receive(success(4, fixture.sources), applied::incrementAndGet);
            verify(fixture.tasks).failImport(any(), eq("OUTPUT_VERIFICATION_FAILED"), contains("duration"));
            verify(fixture.tasks, never()).completeImport(any(), anyList());
            assertEquals(1, applied.get());
            assertEquals(1, source.requests.get());
        }
    }
    @Test void inspectionTechnicalFailureAndMissingToolRemainUnappliedAndDoNotResubmitOrRetryDownload() throws Exception {
        for (IOException failure : List.of(new IOException("Storage unavailable"),
                new GenerationMediaInspector.InspectorUnavailableException("Probe missing", new IOException("Missing binary")))) {
            try (Source source = new Source(png())) {
                ImportFixture fixture = new ImportFixture(source.url(), imageRequest(9, 7));
                GenerationMediaInspector inspector = mock(GenerationMediaInspector.class);
                when(inspector.inspect(any(), any())).thenThrow(failure);
                AtomicInteger applied = new AtomicInteger();
                assertThrows(IllegalStateException.class, () -> fixture.service(inspector).receive(success(4, fixture.sources), applied::incrementAndGet));
                assertEquals(1, source.requests.get());
                assertEquals(0, applied.get());
                verify(fixture.tasks, never()).failImport(any(), anyString(), anyString());
                verify(fixture.tasks, never()).completeImport(any(), anyList());
            }
        }
    }
    private static TaskService service(TaskRepository repository, OutboxRepository outbox) {
        return new TaskService(repository, mock(ChatRepository.class), mock(AssetRepository.class), outbox, mock(SessionService.class), transactions());
    }
    private static ImageGenerationRequest imageRequest(int width, int height) {
        PixelSize size = new PixelSize("pixel_size", new PixelDimensions(width, height));
        return new ImageGenerationRequest("image_generation_plan_v1", 1, new ModelSelection("fixture", "fixture", 1L),
            new ProviderBinding("fixture", "fixture", "fixture"),
            new ImageExecutionParameters("image_execution_parameters", "fixture", size, List.of(), ImageOutputFormat.PNG, ImageMode.values()[0]),
            new ImageResultExpectations("image_result_expectations", 1, size, ImageOutputFormat.PNG));
    }
    private static TaskRecord normalizedTask(NormalizedGenerationRequest request, String status, List<ResultSource> sources) {
        return new TaskRecord(TASK, OWNER, SESSION, OPERATION, request instanceof ImageGenerationRequest ? "IMAGE" : "VIDEO", ConnectorRequest.normalized(request), status, 2, 3,
            sources, null, null, null, null, Instant.EPOCH, Instant.EPOCH, UUID.randomUUID().toString());
    }
    private static ResultSource source(String url) { return new ResultSource(url, "result.png", "image/png"); }
    private static byte[] png() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(9, 7, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
    private final class ImportFixture {
        final TaskService tasks = mock(TaskService.class);
        final AssetRepository assets = mock(AssetRepository.class);
        final LocalAssetStore store = new LocalAssetStore(directory.toString());
        final List<ResultSource> sources;
        final AtomicInteger inspections = new AtomicInteger();
        AssetRecord record;
        ImportFixture(String url, NormalizedGenerationRequest request) {
            sources = List.of(request instanceof VideoGenerationRequest
                ? new ResultSource(url, "result.mp4", "video/mp4") : source(url));
            TaskService.ImportPlan plan = new TaskService.ImportPlan(normalizedTask(request, "IMPORTING", sources), sources);
            when(tasks.admitGeneration(any(), any())).thenReturn(plan);
            when(assets.find(anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(record));
            when(tasks.registerImportAsset(any(), any(), anyInt())).thenAnswer(call -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                record = call.getArgument(1);
                return record;
            });
        }
        TaskResultService service(GenerationMediaInspector inspector) {
            return new TaskResultService(tasks, assets, store, transactions(), 1024, 2000, inspector, new GenerationResultVerifier());
        }
    }
    private static final class Source implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        Source(byte[] bytes) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/result", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(200, bytes.length);
                try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/result"; }
        @Override public void close() { server.stop(0); }
    }
}
