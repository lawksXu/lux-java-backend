package com.lux.backend.task;

import com.lux.backend.asset.AssetRecord;
import com.lux.backend.asset.AssetRepository;
import com.lux.backend.asset.AssetStore;
import com.lux.backend.asset.LocalAssetStore;
import com.lux.backend.infrastructure.IncomingEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static com.lux.backend.task.TaskTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskResultServiceTests {
    @TempDir Path directory;

    @Test void importsOutsideTransactionsAndAppliesInboxOnlyAfterTerminalWork() throws Exception {
        try (Source source = new Source(200, "result".getBytes(), 6)) {
            Fixture fixture = new Fixture(source);
            AtomicBoolean completed = new AtomicBoolean();
            doAnswer(call -> { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); completed.set(true); return null; })
                    .when(fixture.tasks).completeImport(any(), anyList());
            AtomicInteger applied = new AtomicInteger();
            fixture.service(240000).receive(fixture.event, () -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(completed.get()); applied.incrementAndGet();
            });
            assertEquals(1, source.requests.get());
            assertEquals(1, applied.get());
            assertEquals("result", Files.readString(fixture.store.path(UUID.fromString(OWNER), UUID.fromString(TaskResultService.sourceId(TASK, 0)))));
            verify(fixture.tasks, never()).failImport(any(), anyString(), anyString());
        }
    }

    @Test void http5xxIsConfirmedImportFailureAndAcknowledgedAfterFailurePersistence() throws Exception {
        try (Source source = new Source(503, "bad".getBytes(), 3)) {
            Fixture fixture = new Fixture(source);
            AtomicBoolean failed = new AtomicBoolean();
            doAnswer(call -> { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); failed.set(true); return null; })
                    .when(fixture.tasks).failImport(any(), eq("IMPORT_SOURCE_HTTP"), anyString());
            AtomicInteger applied = new AtomicInteger();
            fixture.service(240000).receive(fixture.event, () -> { assertTrue(failed.get()); applied.incrementAndGet(); });
            assertEquals(1, applied.get());
            assertEquals(1, source.requests.get());
            assertTrue(fixture.records.isEmpty());
            verify(fixture.tasks, never()).completeImport(any(), any());
        }
    }

    @Test void truncatedContentCannotPublishObjectOrCompleteTask() throws Exception {
        try (Source source = new Source(200, "short".getBytes(), 12)) {
            Fixture fixture = new Fixture(source);
            AtomicInteger applied = new AtomicInteger();
            fixture.service(240000).receive(fixture.event, applied::incrementAndGet);
            verify(fixture.tasks).failImport(any(), anyString(), anyString());
            assertEquals(1, applied.get());
            assertTrue(fixture.records.isEmpty());
            assertFalse(Files.exists(fixture.store.path(UUID.fromString(OWNER), UUID.fromString(TaskResultService.sourceId(TASK, 0)))));
        }
    }

    @Test void emptySourceFailsBeforeObjectPublication() throws Exception {
        try (Source source = new Source(200, new byte[0], -1)) {
            Fixture fixture = new Fixture(source);
            fixture.service(240000).receive(fixture.event, () -> {});
            verify(fixture.tasks).failImport(any(), eq("IMPORT_SOURCE_EMPTY"), anyString());
            assertTrue(fixture.records.isEmpty());
            assertFalse(Files.exists(fixture.store.path(UUID.fromString(OWNER), UUID.fromString(TaskResultService.sourceId(TASK, 0)))));
        }
    }

    @Test void uncertainObjectPublicationPropagatesAndRedeliveryReusesSameAssetIdentity() throws Exception {
        try (Source source = new Source(200, "result".getBytes(), 6)) {
            Fixture fixture = new Fixture(source);
            AssetStore uncertain = mock(AssetStore.class);
            AtomicInteger writes = new AtomicInteger();
            when(uncertain.write(any(), any(), any(), anyLong(), any())).thenAnswer(call -> {
                AssetStore.StoredFile saved = fixture.store.write(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), call.getArgument(4));
                if (writes.getAndIncrement() == 0) throw new IOException("Object publication acknowledgment lost");
                return saved;
            });
            when(uncertain.matches(any(), any(), anyLong(), anyString(), any())).thenAnswer(call ->
                    fixture.store.matches(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
            TaskResultService service = new TaskResultService(fixture.tasks, fixture.assets, uncertain, transactions(), 1024, 240000);
            AtomicInteger applied = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> service.receive(fixture.event, applied::incrementAndGet));
            assertEquals(0, applied.get());
            verify(fixture.tasks, never()).failImport(any(), anyString(), anyString());
            service.receive(fixture.event, applied::incrementAndGet);
            assertEquals(1, applied.get());
            assertEquals(2, source.requests.get());
            assertEquals(1, fixture.records.size());
            assertEquals("result", Files.readString(fixture.store.path(UUID.fromString(OWNER), UUID.fromString(TaskResultService.sourceId(TASK, 0)))));
        }
    }

    @Test void databaseFailureDoesNotBecomeTaskFailureOrConsumptionSuccess() throws Exception {
        try (Source source = new Source(200, "result".getBytes(), 6)) {
            Fixture fixture = new Fixture(source);
            doThrow(new IllegalStateException("database unavailable")).when(fixture.tasks).completeImport(any(), anyList());
            AtomicInteger applied = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> fixture.service(240000).receive(fixture.event, applied::incrementAndGet));
            assertEquals(0, applied.get());
            verify(fixture.tasks, never()).failImport(any(), anyString(), anyString());
            assertEquals(1, fixture.records.size());
        }
    }

    @Test void wholeCallbackBudgetBlocksFurtherIoWithoutTaskFailure() throws Exception {
        try (Source source = new Source(200, "result".getBytes(), 6)) {
            Fixture fixture = new Fixture(source);
            when(fixture.tasks.admitGeneration(any(), any())).thenAnswer(call -> {
                Thread.sleep(20); return fixture.plan;
            });
            AtomicInteger applied = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> fixture.service(1).receive(fixture.event, applied::incrementAndGet));
            assertEquals(0, source.requests.get());
            assertEquals(0, applied.get());
            verify(fixture.tasks, never()).failImport(any(), anyString(), anyString());
        }
    }

    @Test void processInterruptionDoesNotAdmitOrAcknowledgeTheRecord() throws Exception {
        try (Source source = new Source(200, "result".getBytes(), 6)) {
            Fixture fixture = new Fixture(source);
            AtomicInteger applied = new AtomicInteger();
            try {
                Thread.currentThread().interrupt();
                assertThrows(IllegalStateException.class, () -> fixture.service(240000).receive(fixture.event, applied::incrementAndGet));
            } finally { Thread.interrupted(); }
            assertEquals(0, source.requests.get());
            assertEquals(0, applied.get());
            verifyNoInteractions(fixture.tasks);
        }
    }

    private final class Fixture {
        final TaskService tasks = mock(TaskService.class);
        final AssetRepository assets = mock(AssetRepository.class);
        final LocalAssetStore store = spy(new LocalAssetStore(directory.toString()));
        final Map<String, AssetRecord> records = new HashMap<>();
        final TaskService.ImportPlan plan;
        final IncomingEvent.Generation event;
        Fixture(Source source) throws Exception {
            List<ResultSource> sources = List.of(new ResultSource(source.url(), "result.png", "image/png"));
            plan = new TaskService.ImportPlan(task("IMPORTING", 4, sources), sources);
            event = success(4, sources);
            when(tasks.admitGeneration(any(), any())).thenReturn(plan);
            when(assets.find(eq(OWNER), anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(1))));
            when(tasks.registerImportAsset(any(), any(), anyInt())).thenAnswer(call -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                AssetRecord record = call.getArgument(1); records.put(record.id(), record); return record;
            });
            doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return call.callRealMethod(); })
                    .when(store).write(any(), any(), any(), anyLong(), any());
            doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return call.callRealMethod(); })
                    .when(store).matches(any(), any(), anyLong(), anyString(), any());
        }
        TaskResultService service(long timeout) { return new TaskResultService(tasks, assets, store, transactions(), 1024, timeout); }
    }

    /** 本地已有 fixture，不访问 provider 或部署服务。 */
    private static final class Source implements AutoCloseable {
        private final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        Source(int status, byte[] bytes, long declaredLength) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/result", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(status, declaredLength);
                try (OutputStream body = exchange.getResponseBody()) { body.write(bytes); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/result"; }
        @Override public void close() { server.stop(0); }
    }
}
