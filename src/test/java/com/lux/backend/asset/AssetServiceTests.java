package com.lux.backend.asset;

import com.lux.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetServiceTests {
    @TempDir Path directory;

    @Test
    void repeatedUploadReturnsExistingRecordAndDeletesOnlyTheNewObject() throws Exception {
        String owner = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        AssetRecord existing = new AssetRecord(UUID.randomUUID().toString(), owner, null,
                "photo.png", "image/png", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Instant.EPOCH);
        AssetRepository repository = mock(AssetRepository.class);
        TransactionTemplate transactions = immediateTransactions();
        when(repository.findByOperation(owner, operation)).thenReturn(Optional.of(existing));
        AssetStore store = mock(AssetStore.class);
        when(store.write(eq(UUID.fromString(owner)), any(UUID.class), any(), eq(1024L)))
                .thenReturn(new AssetStore.StoredFile(existing.sizeBytes(), existing.sha256()));
        AssetService service = new AssetService(repository, store, transactions, 1024);

        AssetRecord returned = service.upload(owner,
                new MockMultipartFile("file", "photo.png", "image/png", "abc".getBytes()), operation);

        assertEquals(existing, returned);
        verify(repository).coordinateOwner(owner);
        ArgumentCaptor<UUID> uploadedId = ArgumentCaptor.forClass(UUID.class);
        verify(store).write(eq(UUID.fromString(owner)), uploadedId.capture(), any(), eq(1024L));
        verify(store).delete(UUID.fromString(owner), uploadedId.getValue());
        verify(store, never()).delete(UUID.fromString(owner), UUID.fromString(existing.id()));
        verify(repository, never()).insert(any(), any(), any());
    }

    @Test
    void repeatedOperationWithDifferentBytesConflictsAndRemovesNewFile() throws Exception {
        String owner = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        AssetRepository repository = mock(AssetRepository.class);
        AssetRecord existing = new AssetRecord(UUID.randomUUID().toString(), owner, null,
                "photo.png", "image/png", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Instant.EPOCH);
        when(repository.findByOperation(owner, operation)).thenReturn(Optional.of(existing));
        AssetService service = new AssetService(repository, new LocalAssetStore(directory.toString()),
                immediateTransactions(), 1024);

        ApiException error = assertThrows(ApiException.class, () -> service.upload(owner,
                new MockMultipartFile("file", "photo.png", "image/png", "xyz".getBytes()), operation));

        assertEquals(409, error.status());
        try (Stream<Path> files = Files.walk(directory)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void rejectsEmptyFileBeforePersistingIt() {
        AssetService service = new AssetService(mock(AssetRepository.class),
                new LocalAssetStore(directory.toString()), immediateTransactions(), 1024);
        ApiException error = assertThrows(ApiException.class, () -> service.upload(UUID.randomUUID().toString(),
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0]), UUID.randomUUID().toString()));
        assertEquals(400, error.status());
        assertTrue(Files.notExists(directory.resolve("empty.png")));
    }

    @Test
    void newUploadPersistsMetadataAndKeepsTheContent() throws Exception {
        String owner = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        AssetRepository repository = mock(AssetRepository.class);
        when(repository.findByOperation(owner, operation)).thenReturn(Optional.empty());
        LocalAssetStore store = new LocalAssetStore(directory.toString());
        AssetService service = new AssetService(repository, store, immediateTransactions(), 1024);

        AssetRecord created = service.upload(owner,
                new MockMultipartFile("file", "folder\\photo.png", "image/png", "abc".getBytes()), operation);

        assertEquals("photo.png", created.filename());
        assertEquals("image/png", created.mediaType());
        assertEquals(3, created.sizeBytes());
        assertEquals("abc", Files.readString(store.path(UUID.fromString(owner), UUID.fromString(created.id()))));
        verify(repository).insert(eq(created), eq(operation), eq(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void optimisticRetryReusesTheStoredObjectWithoutReuploading() throws Exception {
        String owner = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        AssetRepository repository = mock(AssetRepository.class);
        doThrow(new OptimisticLockingFailureException("Concurrent upload"))
                .doNothing().when(repository).coordinateOwner(owner);
        when(repository.findByOperation(owner, operation)).thenReturn(Optional.empty());
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            assertThrows(OptimisticLockingFailureException.class,
                    () -> callback.doInTransaction(mock(TransactionStatus.class)));
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        AssetStore store = mock(AssetStore.class);
        when(store.write(eq(UUID.fromString(owner)), any(UUID.class), any(), eq(1024L)))
                .thenReturn(new AssetStore.StoredFile(3,
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        AssetService service = new AssetService(repository, store, transactions, 1024);
        MockMultipartFile file = spy(new MockMultipartFile("file", "photo.png", "image/png", "abc".getBytes()));

        AssetRecord created = service.upload(owner, file, operation);

        verify(file).getInputStream();
        verify(store).write(eq(UUID.fromString(owner)), eq(UUID.fromString(created.id())), any(), eq(1024L));
        verify(store, never()).delete(any(), any());
        verify(repository).insert(created, operation, null);
    }

    @SuppressWarnings("unchecked")
    private static TransactionTemplate immediateTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        return transactions;
    }
}
