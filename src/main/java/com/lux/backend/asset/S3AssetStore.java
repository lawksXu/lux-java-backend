package com.lux.backend.asset;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/**
 * 通过同步 S3 SDK 访问私有对象存储，以“用户 UUID/资源 UUID”作为稳定对象键。
 * 新内容先暂存并计算摘要；仅确认对象缺失时回退读取旧本地文件，网络和权限错误直接上报。
 */
public class S3AssetStore implements AssetStore {
    /** 同步 S3 客户端，执行签名后的对象存储请求。 */
    private final S3Client client;
    /** 存放 Lux 私有资源的 S3 桶名。 */
    private final String bucket;
    /** S3 上传前的私有本地暂存目录，使 SDK 可重复读取完整请求体。 */
    private final Path staging;
    /** 旧本地文件存储，仅在 S3 对象确实缺失时提供读取回退。 */
    private final LocalAssetStore legacy;
    /** 本进程正在使用的 S3 暂存文件，后台清理必须跳过。 */
    private final Set<Path> active = ConcurrentHashMap.newKeySet();

    /** 绑定 S3 客户端、桶、暂存目录与旧文件读取来源。 */
    public S3AssetStore(S3Client client, String bucket, Path staging, LocalAssetStore legacy) {
        this.client = client;
        this.bucket = bucket;
        this.staging = staging.toAbsolutePath().normalize();
        this.legacy = legacy;
    }

    /** 先在私有目录暂存、检查字节上限并计算摘要，再上传到稳定对象键；支持可选的既有摘要校验。 */
    @Override public StoredFile write(UUID user, UUID asset, InputStream input, long maxBytes) throws IOException {
        return write(user, asset, input, maxBytes, TransferDeadline.afterMillis(180_000));
    }

    @Override public StoredFile write(UUID user, UUID asset, InputStream input, long maxBytes,
                                      TransferDeadline deadline) throws IOException {
        return write(user, asset, input, maxBytes, null, null, deadline);
    }

    /** 验证既有资源的期望元数据，防止导入重试覆盖成不同内容。 */
    @Override public StoredFile writeVerified(UUID user, UUID asset, InputStream input, long maxBytes,
                                             long expectedSize, String expectedSha256) throws IOException {
        if (expectedSize < 0 || expectedSha256 == null) throw new IllegalArgumentException("Expected metadata is required");
        return write(user, asset, input, maxBytes, expectedSize, expectedSha256,
                TransferDeadline.afterMillis(180_000));
    }

    /** 先在私有目录暂存、检查字节上限并计算摘要，再上传到稳定对象键；支持可选的既有摘要校验。 */
    private StoredFile write(UUID user, UUID asset, InputStream input, long maxBytes,
                             Long expectedSize, String expectedHash, TransferDeadline deadline) throws IOException {
        deadline.check();
        if (maxBytes < 0) throw new IllegalArgumentException("Maximum size must not be negative");
        String key = key(user, asset);
        java.util.Objects.requireNonNull(input, "input");
        Files.createDirectories(staging.getParent());
        LocalAssetStore.ensurePrivateDirectory(staging);
        Path temporary;
        synchronized (active) {
            temporary = Files.getFileStore(staging).supportsFileAttributeView("posix")
                    ? Files.createTempFile(staging, "lux-s3-", ".tmp",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(staging, "lux-s3-", ".tmp");
            active.add(temporary);
        }
        try {
            MessageDigest digest = digest();
            long size = 0;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    deadline.check();
                    if (count > maxBytes - size) throw new AssetTooLargeException(maxBytes);
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    size += count;
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            if (expectedSize != null && (size != expectedSize || !hash.equals(expectedHash)))
                throw new IOException("Result source no longer matches persisted asset");
            Duration remaining = deadline.remaining();
            // 条件创建稳定对象键；412 只校验复用赢家字节，不能退化为覆盖式上传。
            try {
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentLength(size)
                        .ifNoneMatch("*").contentType("application/octet-stream").metadata(Map.of("sha256", hash))
                        .overrideConfiguration(config -> config.apiCallTimeout(remaining)
                                .apiCallAttemptTimeout(remaining.compareTo(Duration.ofSeconds(60)) > 0
                                        ? Duration.ofSeconds(60) : remaining)).build(), RequestBody.fromFile(temporary));
            } catch (S3Exception existing) {
                if (existing.statusCode() != 412) throw existing;
                if (!matches(user, asset, size, hash, deadline)) {
                    throw new AssetConflictException("Existing asset has different bytes");
                }
            }
            deadline.check();
            return new StoredFile(size, hash);
        } catch (SdkException failure) {
            throw storageFailure(failure);
        } finally {
            synchronized (active) {
                try { Files.deleteIfExists(temporary); }
                finally { active.remove(temporary); }
            }
        }
    }

    /** 使用 S3 Range GET 读取指定区间并校验返回长度；确认对象缺失时才读取旧本地文件。 */
    @Override public InputStream open(UUID user, UUID asset, long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || length > Long.MAX_VALUE - offset)
            throw new IllegalArgumentException("Invalid object range");
        if (length == 0) {
            if (!exists(user, asset)) throw missing();
            return InputStream.nullInputStream();
        }
        try {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key(user, asset))
                    .range("bytes=" + offset + "-" + (offset + length - 1)).build());
            if (stream.response().contentLength() == null || stream.response().contentLength() != length) {
                stream.close();
                throw new IOException("Object storage returned an unexpected content length");
            }
            return new AssetContentStream(stream, length);
        } catch (S3Exception failure) {
            if (canReadLegacy(failure)) return legacy.open(user, asset, offset, length);
            throw storageFailure(failure);
        } catch (SdkException failure) { throw storageFailure(failure); }
    }

    /** 以 HEAD 检查对象存在性，只有确认对象缺失时才检查旧本地文件。 */
    @Override public boolean exists(UUID user, UUID asset) throws IOException {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(user, asset)).build());
            return true;
        } catch (S3Exception failure) {
            if (canReadLegacy(failure)) return legacy.exists(user, asset);
            throw storageFailure(failure);
        } catch (SdkException failure) { throw storageFailure(failure); }
    }

    /** 先核对对象长度，再实际读取内容计算摘要，不仅依赖对象元数据中的哈希声明。 */
    @Override public boolean matches(UUID user, UUID asset, long size, String hash) throws IOException {
        return matches(user, asset, size, hash, TransferDeadline.afterMillis(180_000));
    }

    @Override public boolean matches(UUID user, UUID asset, long size, String hash,
                                     TransferDeadline deadline) throws IOException {
        deadline.check();
        try {
            Duration headRemaining = deadline.remaining();
            HeadObjectResponse metadata = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(user, asset))
                    .overrideConfiguration(config -> config.apiCallTimeout(headRemaining)).build());
            if (metadata.contentLength() == null || metadata.contentLength() != size) return false;
            MessageDigest digest = digest();
            Duration getRemaining = deadline.remaining();
            try (InputStream stream = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key(user, asset))
                    .overrideConfiguration(config -> config.apiCallTimeout(getRemaining)).build())) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    deadline.check();
                    digest.update(buffer, 0, count);
                }
            }
            deadline.check();
            return HexFormat.of().formatHex(digest.digest()).equals(hash);
        } catch (S3Exception failure) {
            if (canReadLegacy(failure)) return legacy.matches(user, asset, size, hash, deadline);
            throw storageFailure(failure);
        } catch (SdkException failure) { throw storageFailure(failure); }
    }

    /** 删除 S3 中的指定对象；旧本地目录中的文件不由此方法删除。 */
    @Override public void delete(UUID user, UUID asset) throws IOException {
        try { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(user, asset)).build()); }
        catch (SdkException failure) { throw storageFailure(failure); }
    }

    /** 清理超龄且未被本进程使用的 S3 暂存文件。 */
    @Override public int cleanupTemporaryFiles(Duration age) throws IOException {
        if (age.isNegative()) throw new IllegalArgumentException("Age must not be negative");
        if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) return 0;
        Instant cutoff = Instant.now().minus(age);
        int removed = 0;
        try (Stream<Path> paths = Files.list(staging)) {
            for (Path file : paths.toList()) {
                synchronized (active) {
                    String name = file.getFileName().toString();
                    if (name.startsWith("lux-s3-") && name.endsWith(".tmp") && !active.contains(file)
                            && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)
                            && Files.deleteIfExists(file)) removed++;
                }
            }
        }
        return removed;
    }

    /** 分页扫描桶内对象，只清理规范用户/资源键中超龄且业务不要求保留的对象。 */
    @Override public void cleanupOrphans(Duration age, BiPredicate<UUID, UUID> retain) throws IOException {
        if (age.isNegative()) throw new IllegalArgumentException("Age must not be negative");
        Instant cutoff = Instant.now().minus(age);
        String token = null;
        try {
            do {
                ListObjectsV2Response page = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                        .continuationToken(token).maxKeys(1000).build());
                for (S3Object object : page.contents()) {
                    if (object.lastModified() == null || !object.lastModified().isBefore(cutoff)) continue;
                    String[] parts = object.key().split("/", -1);
                    // 清理只识别本服务拥有的精确 UUID/UUID 键，忽略其他目录形状或非规范身份。
                    if (parts.length != 2) continue;
                    UUID user, asset;
                    try {
                        user = UUID.fromString(parts[0]); asset = UUID.fromString(parts[1]);
                        if (!key(user, asset).equals(object.key())) continue;
                    } catch (IllegalArgumentException invalid) { continue; }
                    if (!retain.test(user, asset)) delete(user, asset);
                }
                if (!Boolean.TRUE.equals(page.isTruncated())) break;
                String next = page.nextContinuationToken();
                if (next == null || next.equals(token)) throw new IOException("Object listing did not advance");
                token = next;
            } while (true);
        } catch (SdkException failure) { throw storageFailure(failure); }
    }

    /** 使用稳定 UUID 生成对象键，使同一结果的导入重试命中同一位置。 */
    private static String key(UUID user, UUID asset) { return user.toString() + "/" + asset.toString(); }
    /** 仅在对象确实缺失且桶仍可访问时允许回退，避免隐藏桶丢失、权限或网络故障。 */
    private boolean canReadLegacy(S3Exception failure) throws IOException {
        if (failure.statusCode() != 404 || (failure.awsErrorDetails() != null
                && "NoSuchBucket".equals(failure.awsErrorDetails().errorCode()))) return false;
        // HEAD 的 404 没有错误响应体，SDK 可能把缺桶也映射为 NoSuchKey；额外检查桶存在后才允许回退。
        try { client.headBucket(HeadBucketRequest.builder().bucket(bucket).build()); }
        catch (SdkException bucketFailure) { throw storageFailure(bucketFailure); }
        return true;
    }
    /** 构造资源内容缺失异常，供上层转换为 404。 */
    private static FileNotFoundException missing() { return new FileNotFoundException("Asset content is missing"); }
    /** 把 SDK 异常包装成存储 I/O 错误并保留原始原因。 */
    private static IOException storageFailure(SdkException failure) {
        return new IOException("Object storage request failed", failure);
    }
    /** 创建本次流式传输使用的 SHA-256 实例。 */
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
