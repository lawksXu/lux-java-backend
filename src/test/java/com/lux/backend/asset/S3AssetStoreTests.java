package com.lux.backend.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3AssetStoreTests {
    @TempDir Path directory;
    private final UUID user = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final S3Client client = mock(S3Client.class);

    private S3AssetStore store() {
        return new S3AssetStore(client, "lux-assets", directory.resolve("staging"),
                new LocalAssetStore(directory.resolve("legacy").toString()));
    }

    @Test void stagesBoundedReplayableBytesWithChecksumAndRemovesTheTemporaryFile() throws Exception {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            RequestBody body = call.getArgument(1);
            assertEquals("lux-assets", request.bucket());
            assertEquals(user + "/" + asset, request.key());
            assertEquals("*", request.ifNoneMatch());
            assertTrue(request.overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow()
                    .compareTo(Duration.ofSeconds(180)) <= 0);
            assertTrue(request.overrideConfiguration().orElseThrow().apiCallAttemptTimeout().orElseThrow()
                    .compareTo(Duration.ofSeconds(60)) <= 0);
            assertEquals(3L, body.optionalContentLength().orElseThrow());
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", request.metadata().get("sha256"));
            for (int attempt = 0; attempt < 2; attempt++) {
                try (InputStream stream = body.contentStreamProvider().newStream()) {
                    assertArrayEquals("abc".getBytes(), stream.readAllBytes());
                }
            }
            return PutObjectResponse.builder().build();
        });
        AssetStore.StoredFile saved = store().write(user, asset, new ByteArrayInputStream("abc".getBytes()), 3);
        assertEquals(3, saved.sizeBytes());
        try (Stream<Path> files = Files.list(directory.resolve("staging"))) { assertEquals(0, files.count()); }
        assertFalse(Files.exists(directory.resolve("legacy").resolve(user.toString()).resolve(asset.toString())));
    }

    @Test void identicalConditionalCollisionVerifiesAndReusesExistingBytes() throws Exception {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).build());
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(3L).build());
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength(3L).build(),
                        new ByteArrayInputStream("abc".getBytes())));

        AssetStore.StoredFile saved = store().write(user, asset, new ByteArrayInputStream("abc".getBytes()), 3);

        assertEquals(3L, saved.sizeBytes());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", saved.sha256());
        verify(client).getObject(any(GetObjectRequest.class));
    }

    @Test void rejectsOversizeOrChangedReplacementBeforeSendingAnyObject() {
        assertThrows(AssetStore.AssetTooLargeException.class,
                () -> store().write(user, asset, new ByteArrayInputStream("abcd".getBytes()), 3));
        assertThrows(IOException.class, () -> store().writeVerified(user, asset,
                new ByteArrayInputStream("changed".getBytes()), 100, 3, "original-hash"));
        verifyNoInteractions(client);
    }

    @Test void expiredWholeTransferBudgetNeverStartsObjectPublicationOrVerification() {
        AssetStore.TransferDeadline expired = new AssetStore.TransferDeadline(System.nanoTime() - 1);
        assertThrows(AssetStore.TransferInterruptedException.class,
                () -> store().write(user, asset, new ByteArrayInputStream("abc".getBytes()), 3, expired));
        assertThrows(AssetStore.TransferInterruptedException.class,
                () -> store().matches(user, asset, 3, "digest", expired));
        verifyNoInteractions(client);
    }

    @Test void sendsTheRequestedRangeAndClosesTheRemoteStream() throws Exception {
        ByteArrayInputStream body = spy(new ByteArrayInputStream("ell".getBytes()));
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength(3L).build(), body));
        try (InputStream stream = store().open(user, asset, 1, 3)) { assertEquals("ell", new String(stream.readAllBytes())); }
        verify(client).getObject(argThat((GetObjectRequest request) -> request.range().equals("bytes=1-3")
                && request.key().equals(user + "/" + asset)));
        verify(body).close();
    }

    @Test void onlyMissingObjectsCanFallBackToLegacyFiles() throws Exception {
        LocalAssetStore legacy = new LocalAssetStore(directory.resolve("legacy").toString());
        legacy.write(user, asset, new ByteArrayInputStream("old".getBytes()), 10);
        S3AssetStore store = store();
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        try (InputStream content = store.open(user, asset, 0, 3)) { assertEquals("old", new String(content.readAllBytes())); }
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(503).build());
        assertThrows(IOException.class, () -> store.open(user, asset, 0, 3));
        store.delete(user, asset);
        assertTrue(Files.exists(legacy.path(user, asset)));
    }

    @Test void integrityChecksReadBytesRatherThanTrustingObjectMetadata() throws Exception {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(3L).metadata(java.util.Map.of("sha256", "expected")).build());
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength(3L).build(),
                        new ByteArrayInputStream("bad".getBytes())));
        assertFalse(store().matches(user, asset, 3, "expected"));
    }

    @Test void missingBucketOrDeniedBucketCheckMustNotUseLegacyData() throws Exception {
        LocalAssetStore legacy = new LocalAssetStore(directory.resolve("legacy").toString());
        AssetStore.StoredFile saved = legacy.write(user, asset, new ByteArrayInputStream("old".getBytes()), 10);
        // The SDK translates every HEAD-object 404, including a missing bucket, to NoSuchKey.
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        for (int status : new int[]{404, 403, 503}) {
            when(client.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(S3Exception.builder().statusCode(status).build());
            assertThrows(IOException.class, () -> store().exists(user, asset));
            assertThrows(IOException.class, () -> store().matches(user, asset, saved.sizeBytes(), saved.sha256()));
            assertThrows(IOException.class, () -> store().open(user, asset, 0, saved.sizeBytes()));
        }
    }

    @Test void cleanupPaginatesAndPreservesRegisteredRecentAndForeignObjects() throws Exception {
        String keep = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        Instant old = Instant.now().minus(Duration.ofDays(3));
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("next")
                        .contents(S3Object.builder().key(user + "/" + keep).lastModified(old).build(),
                                S3Object.builder().key("foreign/file").lastModified(old).build()).build(),
                ListObjectsV2Response.builder().isTruncated(false).contents(
                        S3Object.builder().key(user + "/" + asset).lastModified(old).build(),
                        S3Object.builder().key(user + "/" + fresh).lastModified(Instant.now()).build()).build());
        store().cleanupOrphans(Duration.ofDays(2), (owner, id) -> id.toString().equals(keep));
        verify(client).listObjectsV2(argThat((ListObjectsV2Request r) -> "next".equals(r.continuationToken())));
        verify(client).deleteObject(argThat((DeleteObjectRequest r) -> r.key().equals(user + "/" + asset)));
        verify(client, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }
}
