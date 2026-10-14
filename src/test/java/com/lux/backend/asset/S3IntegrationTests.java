package com.lux.backend.asset;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class S3IntegrationTests {
    @TempDir static Path directory;
    private static final String BUCKET = "lux-s3-test-" + UUID.randomUUID();
    private static final Set<String> objects = new LinkedHashSet<>();
    private static final Map<String, String> uploads = new LinkedHashMap<>();
    private static GenericContainer<?> container;
    private static URI endpoint;
    private static S3Client client;
    private static S3AssetStore store;
    private static boolean bucketCreated;

    @BeforeAll
    static void startStorage() throws Exception {
        String configuredEndpoint = System.getenv("LUX_TEST_S3_ENDPOINT");
        String accessKey;
        String secretKey;
        if (configuredEndpoint != null && !configuredEndpoint.isBlank()) {
            endpoint = URI.create(configuredEndpoint);
            accessKey = requiredEnvironment("LUX_S3_ACCESS_KEY");
            secretKey = requiredEnvironment("LUX_S3_SECRET_KEY");
        } else {
            accessKey = "lux-integration-" + UUID.randomUUID();
            secretKey = UUID.randomUUID().toString();
            container = new GenericContainer<>("chrislusf/seaweedfs:4.47")
                    .withCommand("mini", "-dir=/data", "-ip=127.0.0.1", "-ip.bind=0.0.0.0", "-s3.port=8333")
                    .withEnv("AWS_ACCESS_KEY_ID", accessKey)
                    .withEnv("AWS_SECRET_ACCESS_KEY", secretKey)
                    .withExposedPorts(8333)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)));
            container.start();
            endpoint = new URI("http", null, container.getHost(), container.getMappedPort(8333), null, null, null);
        }
        String region = System.getenv().getOrDefault("LUX_S3_REGION", "us-east-1");
        client = new S3StorageConfiguration().assetS3Client(endpoint, region, accessKey, secretKey);
        awaitStorage();
        client.createBucket(request -> request.bucket(BUCKET));
        bucketCreated = true;
        store = new S3AssetStore(client, BUCKET, directory.resolve("staging"),
                new LocalAssetStore(directory.resolve("legacy").toString()));
    }

    @Test
    void storesPrivateAssetsWithMetadataFullAndRangedReadsAndDeletion() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        String key = owner + "/" + asset;
        objects.add(key);
        byte[] content = "real SDK asset content".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));

        AssetStore.StoredFile saved = store.write(owner, asset, new ByteArrayInputStream(content), content.length);

        assertEquals(content.length, saved.sizeBytes());
        assertEquals(sha256, saved.sha256());
        HeadObjectResponse head = client.headObject(request -> request.bucket(BUCKET).key(key));
        assertEquals(content.length, head.contentLength());
        assertEquals(sha256, head.metadata().get("sha256"));
        assertTrue(store.exists(owner, asset));
        assertTrue(store.matches(owner, asset, content.length, sha256));
        assertFalse(store.matches(owner, asset, content.length + 1, sha256));
        assertFalse(store.matches(owner, asset, content.length, "0".repeat(64)));
        assertArrayEquals(content, client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)).asByteArray());
        try (InputStream full = store.open(owner, asset, 0, content.length)) {
            assertArrayEquals(content, full.readAllBytes());
        }
        try (InputStream range = store.open(owner, asset, 5, 3)) {
            assertArrayEquals(Arrays.copyOfRange(content, 5, 8), range.readAllBytes());
        }
        assertEquals(List.of(key), client.listObjectsV2(request -> request.bucket(BUCKET).prefix(owner + "/"))
                .contents().stream().map(S3Object::key).toList());

        HttpClient anonymous = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.toString().replaceAll("/$", "") + "/" + BUCKET + "/" + key))
                .timeout(Duration.ofSeconds(5)).GET().build();
        assertEquals(403, anonymous.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());

        assertEquals(saved, store.writeVerified(owner, asset, new ByteArrayInputStream(content), content.length,
                saved.sizeBytes(), saved.sha256()));
        assertThrows(AssetStore.AssetConflictException.class, () -> store.write(owner, asset,
                new ByteArrayInputStream("different".getBytes(StandardCharsets.UTF_8)), content.length));
        assertArrayEquals(content, client.getObjectAsBytes(requestBuilder -> requestBuilder.bucket(BUCKET).key(key)).asByteArray());
        store.delete(owner, asset);
        assertFalse(store.exists(owner, asset));
        assertThrows(FileNotFoundException.class, () -> store.open(owner, asset, 0, content.length));
        assertTrue(client.listObjectsV2(builder -> builder.bucket(BUCKET).prefix(owner + "/")).contents().isEmpty());
    }

    @Test
    void completesAndAbortsRealMultipartUploads() {
        String key = "multipart/" + UUID.randomUUID();
        objects.add(key);
        byte[] first = new byte[5 * 1024 * 1024];
        new Random(1234).nextBytes(first);
        byte[] last = "final part".getBytes(StandardCharsets.UTF_8);
        String uploadId = client.createMultipartUpload(request -> request.bucket(BUCKET).key(key)).uploadId();
        uploads.put(uploadId, key);

        List<CompletedPart> completed = new ArrayList<>();
        for (int number = 1; number <= 2; number++) {
            byte[] bytes = number == 1 ? first : last;
            UploadPartResponse part = client.uploadPart(UploadPartRequest.builder().bucket(BUCKET).key(key)
                    .uploadId(uploadId).partNumber(number).build(), RequestBody.fromBytes(bytes));
            completed.add(CompletedPart.builder().partNumber(number).eTag(part.eTag()).build());
        }
        List<Part> parts = client.listParts(request -> request.bucket(BUCKET).key(key).uploadId(uploadId)).parts();
        assertEquals(List.of(1, 2), parts.stream().map(Part::partNumber).toList());
        assertEquals(List.of((long) first.length, (long) last.length), parts.stream().map(Part::size).toList());
        client.completeMultipartUpload(request -> request.bucket(BUCKET).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()));
        uploads.remove(uploadId);

        byte[] expected = Arrays.copyOf(first, first.length + last.length);
        System.arraycopy(last, 0, expected, first.length, last.length);
        assertEquals(expected.length, client.headObject(request -> request.bucket(BUCKET).key(key)).contentLength());
        assertArrayEquals(expected, client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)).asByteArray());
        assertArrayEquals(Arrays.copyOfRange(expected, first.length - 2, first.length + 3),
                client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)
                        .range("bytes=" + (first.length - 2) + "-" + (first.length + 2))).asByteArray());

        String abortedKey = "aborted/" + UUID.randomUUID();
        objects.add(abortedKey);
        String abortedId = client.createMultipartUpload(request -> request.bucket(BUCKET).key(abortedKey)).uploadId();
        uploads.put(abortedId, abortedKey);
        client.uploadPart(UploadPartRequest.builder().bucket(BUCKET).key(abortedKey).uploadId(abortedId)
                .partNumber(1).build(), RequestBody.fromBytes(last));
        assertTrue(client.listMultipartUploads(request -> request.bucket(BUCKET)).uploads().stream()
                .anyMatch(upload -> abortedId.equals(upload.uploadId()) && abortedKey.equals(upload.key())));
        client.abortMultipartUpload(request -> request.bucket(BUCKET).key(abortedKey).uploadId(abortedId));
        uploads.remove(abortedId);
        assertTrue(client.listMultipartUploads(request -> request.bucket(BUCKET)).uploads().isEmpty());
        assertEquals(404, assertThrows(S3Exception.class,
                () -> client.headObject(request -> request.bucket(BUCKET).key(abortedKey))).statusCode());
    }

    @AfterAll
    static void removeOnlyTestStorage() {
        try {
            if (client != null && bucketCreated) {
                for (Map.Entry<String, String> upload : uploads.entrySet()) {
                    try {
                        client.abortMultipartUpload(request -> request.bucket(BUCKET)
                                .key(upload.getValue()).uploadId(upload.getKey()));
                    } catch (S3Exception failure) {
                        if (failure.statusCode() != 404) throw failure;
                    }
                }
                for (String key : objects) client.deleteObject(request -> request.bucket(BUCKET).key(key));
                client.deleteBucket(request -> request.bucket(BUCKET));
            }
        } finally {
            try {
                if (client != null) client.close();
            } finally {
                if (container != null) container.stop();
            }
        }
    }

    private static void awaitStorage() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        SdkException lastFailure = null;
        do {
            try {
                client.listBuckets(request -> request.overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(3)).apiCallAttemptTimeout(Duration.ofSeconds(3))));
                return;
            } catch (SdkException failure) {
                lastFailure = failure;
                Thread.sleep(250);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("S3 storage was not ready within 45 seconds", lastFailure);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for external S3 tests");
        return value;
    }
}
