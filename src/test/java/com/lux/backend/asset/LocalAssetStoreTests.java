package com.lux.backend.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalAssetStoreTests {

    @TempDir
    Path root;

    @Test
    void writesBytesUnderUserAndAssetIdsWithDigest() throws Exception {
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        byte[] content = "asset content".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        LocalAssetStore.StoredFile result = store.write(userId, assetId,
                new ByteArrayInputStream(content), content.length);

        assertEquals(content.length, result.sizeBytes());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                result.sha256());
        assertEquals(root.resolve(userId.toString()).resolve(assetId.toString()).toAbsolutePath(),
                store.path(userId, assetId));
        assertArrayEquals(content, Files.readAllBytes(store.path(userId, assetId)));
    }

    @Test
    void rejectsOversizedStreamWithoutPublishingFileOrTemporaryFile() throws Exception {
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();

        assertThrows(LocalAssetStore.AssetTooLargeException.class, () -> store.write(userId, assetId,
                new ByteArrayInputStream(new byte[9]), 8));

        assertFalse(Files.exists(store.path(userId, assetId)));
        assertEquals(0, countFiles(root));
    }

    @Test
    void retryWithSameAssetIdCannotReplaceCompletedFileWithDifferentBytes() throws Exception {
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        store.write(userId, assetId, new ByteArrayInputStream(new byte[] {1, 2}), 10);

        assertThrows(IOException.class, () -> store.write(userId, assetId,
                new ByteArrayInputStream(new byte[] {3, 4, 5}), 10));

        assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(store.path(userId, assetId)));
        assertEquals(1, countFiles(root));
    }

    @Test
    void identicalRetryReusesTheImmutableObject() throws Exception {
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        byte[] content = new byte[] {1, 2};
        AssetStore.StoredFile original = store.write(userId, assetId, new ByteArrayInputStream(content), 10);

        AssetStore.StoredFile repeated = store.write(userId, assetId, new ByteArrayInputStream(content), 10);

        assertEquals(original, repeated);
        assertArrayEquals(content, Files.readAllBytes(store.path(userId, assetId)));
        assertEquals(1, countFiles(root));
    }

    @Test
    void interruptionDoesNotPublishAnObject() throws Exception {
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, () -> store.write(userId, assetId,
                    new ByteArrayInputStream(new byte[] {1}), 10));
        } finally {
            Thread.interrupted();
        }
        assertFalse(Files.exists(store.path(userId, assetId)));
    }

    @Test
    void failedRetryPreservesCompletedFileAndRemovesTemporaryFile() throws Exception {
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        byte[] original = new byte[] {1, 2, 3};
        store.write(userId, assetId, new ByteArrayInputStream(original), 10);

        assertThrows(IOException.class, () -> store.write(userId, assetId,
                new FailingStream(), 10));

        assertArrayEquals(original, Files.readAllBytes(store.path(userId, assetId)));
        assertEquals(1, countFiles(root));
    }

    @Test
    void matchesDetectsReadableFileWithWrongBytes() throws Exception {
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        AssetStore.StoredFile original = store.write(userId, assetId, new ByteArrayInputStream("original".getBytes()), 100);
        Files.writeString(store.path(userId, assetId), "wrong!!!");

        assertFalse(store.matches(userId, assetId, original.sizeBytes(), original.sha256()));
    }

    @Test
    void verifiedRetryRejectsDifferentBytesBeforeReplacingCommittedFile() throws Exception {
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        AssetStore.StoredFile original = store.write(userId, assetId, new ByteArrayInputStream("original".getBytes()), 100);
        Path path = store.path(userId, assetId);

        assertThrows(IOException.class, () -> store.writeVerified(userId, assetId,
                new ByteArrayInputStream("different".getBytes()), 100,
                original.sizeBytes(), original.sha256()));

        assertEquals("original", Files.readString(path));
        assertEquals(1, countFiles(root));
    }

    @Test
    void cleanupRemovesOnlyAgedTemporaryFiles() throws Exception {
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        store.write(userId, assetId, new ByteArrayInputStream(new byte[] {1}), 10);
        Path userDirectory = store.path(userId, assetId).getParent();
        Path oldTemporary = Files.createFile(userDirectory.resolve(assetId + "-old.tmp"));
        Path recentTemporary = Files.createFile(userDirectory.resolve(assetId + "-recent.tmp"));
        Files.setLastModifiedTime(oldTemporary,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - Duration.ofHours(2).toMillis()));

        assertEquals(1, store.cleanupTemporaryFiles(Duration.ofHours(1)));
        assertFalse(Files.exists(oldTemporary));
        assertTrue(Files.exists(recentTemporary));
        assertTrue(Files.exists(store.path(userId, assetId)));
    }

    @Test
    void createsPrivateUserDirectoryAndFile() throws Exception {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();

        store.write(userId, assetId, new ByteArrayInputStream(new byte[] {1}), 10);

        assertFalse(Files.getPosixFilePermissions(store.path(userId, assetId).getParent()).stream()
                .anyMatch(LocalAssetStoreTests::isGroupOrOtherPermission));
        assertFalse(Files.getPosixFilePermissions(store.path(userId, assetId)).stream()
                .anyMatch(LocalAssetStoreTests::isGroupOrOtherPermission));
    }

    @Test
    void cleanupDoesNotDeleteAnActiveUpload() throws Exception {
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AssetStore.StoredFile> write = executor.submit(() -> store.write(userId, assetId,
                    new BlockingStream(readStarted, releaseRead), 10));
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));

            assertEquals(1, countFiles(root));
            assertEquals(0, store.cleanupTemporaryFiles(Duration.ZERO));

            releaseRead.countDown();
            assertEquals(1, write.get(5, TimeUnit.SECONDS).sizeBytes());
            assertArrayEquals(new byte[] {42}, Files.readAllBytes(store.path(userId, assetId)));
        } finally {
            releaseRead.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void tightensPermissionsOnExistingRootAndUserDirectory() throws Exception {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        LocalAssetStore store = new LocalAssetStore(root.toString());
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        Path userDirectory = store.path(userId, assetId).getParent();
        Files.createDirectory(userDirectory);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwxrwx"));
        Files.setPosixFilePermissions(userDirectory, PosixFilePermissions.fromString("rwxrwxrwx"));

        store.write(userId, assetId, new ByteArrayInputStream(new byte[] {1}), 10);

        assertFalse(Files.getPosixFilePermissions(root).stream()
                .anyMatch(LocalAssetStoreTests::isGroupOrOtherPermission));
        assertFalse(Files.getPosixFilePermissions(userDirectory).stream()
                .anyMatch(LocalAssetStoreTests::isGroupOrOtherPermission));
    }

    private static boolean isGroupOrOtherPermission(PosixFilePermission permission) {
        return permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_");
    }

    private static long countFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static final class FailingStream extends InputStream {
        private boolean delivered;

        @Override
        public int read() throws IOException {
            if (delivered) {
                throw new IOException("transfer interrupted");
            }
            delivered = true;
            return 42;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (delivered) {
                throw new IOException("transfer interrupted");
            }
            delivered = true;
            bytes[offset] = 42;
            return 1;
        }
    }

    private static final class BlockingStream extends InputStream {
        private final CountDownLatch readStarted;
        private final CountDownLatch releaseRead;
        private boolean delivered;

        private BlockingStream(CountDownLatch readStarted, CountDownLatch releaseRead) {
            this.readStarted = readStarted;
            this.releaseRead = releaseRead;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (delivered) {
                return -1;
            }
            readStarted.countDown();
            try {
                if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test read timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("test read interrupted", exception);
            }
            delivered = true;
            bytes[offset] = 42;
            return 1;
        }
    }
}
