package com.lux.backend.asset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/**
 * 本地磁盘存储实现，按用户 UUID 和资源 UUID 组织文件。
 * 先完整写临时文件再原子创建不可变目标；在 S3 模式下也作为旧文件的读取来源。
 */
@Component
public class LocalAssetStore implements AssetStore {

    /** 流式复制和摘要计算的缓冲区大小，避免一次把整个文件装入内存。 */
    private static final int BUFFER_SIZE = 8192;
    /** POSIX 目录权限 700，仅当前用户可读写和进入。 */
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    /** POSIX 文件权限 600，仅当前用户可读写。 */
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    /** 规范化后的本地存储根目录，资源按用户与资源 UUID 分层保存。 */
    private final Path root;
    /** 本进程仍在写入的临时文件集合，清理时在同一同步边界内检查。 */
    private final Set<Path> activeTemporaryFiles = new HashSet<>();

    /** 把配置中的根目录转换为规范绝对路径，供后续文件定位使用。 */
    public LocalAssetStore(@Value("${lux.storage.root:./data/assets}") String root) {
        this.root = Path.of(Objects.requireNonNull(root, "root")).toAbsolutePath().normalize();
    }

    /** 将输入流写到本地资源路径，具体暂存与校验由 writeInternal 完成。 */
    public StoredFile write(UUID userId, UUID assetId, InputStream input, long maxBytes) throws IOException {
        return write(userId, assetId, input, maxBytes, TransferDeadline.afterMillis(180_000));
    }

    @Override
    public StoredFile write(UUID userId, UUID assetId, InputStream input, long maxBytes,
                            TransferDeadline deadline) throws IOException {
        return writeInternal(userId, assetId, input, maxBytes, null, null, deadline);
    }

    /** 要求提供既有资源的长度和摘要，验证通过后只创建缺失对象或复用相同字节。 */
    public StoredFile writeVerified(UUID userId, UUID assetId, InputStream input, long maxBytes,
                                    long expectedSize, String expectedSha256) throws IOException {
        if (expectedSize < 0 || expectedSha256 == null) {
            throw new IllegalArgumentException("Expected asset metadata is required");
        }
        return writeInternal(userId, assetId, input, maxBytes, expectedSize, expectedSha256,
                TransferDeadline.afterMillis(180_000));
    }

    /** 在目标目录中暂存并计算摘要，通过限额与一致性检查后原子创建不可变目标。 */
    private StoredFile writeInternal(UUID userId, UUID assetId, InputStream input, long maxBytes,
                                     Long expectedSize, String expectedSha256, TransferDeadline deadline) throws IOException {
        Objects.requireNonNull(input, "input");
        deadline.check();
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }

        Path target = path(userId, assetId);
        if (root.getParent() != null) {
            Files.createDirectories(root.getParent());
        }
        ensurePrivateDirectory(root);
        ensurePrivateDirectory(target.getParent());
        Path temporary;
        synchronized (activeTemporaryFiles) {
            if (Files.getFileStore(target.getParent()).supportsFileAttributeView("posix")) {
                temporary = Files.createTempFile(target.getParent(), assetId + "-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
            } else {
                temporary = Files.createTempFile(target.getParent(), assetId + "-", ".tmp");
            }
            activeTemporaryFiles.add(temporary);
        }
        try {
            MessageDigest digest = sha256Digest();
            long size = 0;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    deadline.check();
                    if (read > maxBytes - size) {
                        throw new AssetTooLargeException(maxBytes);
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
            StoredFile stored = new StoredFile(size, HexFormat.of().formatHex(digest.digest()));
            if (expectedSize != null && (stored.sizeBytes() != expectedSize
                    || !stored.sha256().equals(expectedSha256))) {
                throw new IOException("Result source no longer matches persisted asset");
            }
            deadline.check();
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
            // 同目录硬链接原子创建最终身份，目标存在时绝不替换，避免旧下载者覆盖新提交的内容。
            try {
                Files.createLink(target, temporary);
            } catch (FileAlreadyExistsException existing) {
                if (!matches(userId, assetId, stored.sizeBytes(), stored.sha256(), deadline)) {
                    throw new AssetConflictException("Existing asset has different bytes");
                }
            }
            // 先刷文件再刷目录项，避免数据库/offset 已提交但崩溃丢失新文件名。
            try (FileChannel directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
            deadline.check();
            return stored;
        } finally {
            synchronized (activeTemporaryFiles) {
                try {
                    Files.deleteIfExists(temporary);
                } finally {
                    activeTemporaryFiles.remove(temporary);
                }
            }
        }
    }

    /** 由规范 UUID 构造“存储根目录/用户/资源”路径，不使用客户端文件名定位文件。 */
    public Path path(UUID userId, UUID assetId) {
        return root.resolve(Objects.requireNonNull(userId, "userId").toString())
                .resolve(Objects.requireNonNull(assetId, "assetId").toString());
    }

    /** 只把普通文件视为存在，不跟随最终路径上的符号链接。 */
    @Override
    public boolean exists(UUID user, UUID asset) {
        return Files.isRegularFile(path(user, asset), LinkOption.NOFOLLOW_LINKS);
    }

    /** 定位文件并跳到指定偏移，再用限定长度的流包装返回。 */
    @Override
    public InputStream open(UUID user, UUID asset, long offset, long length) throws IOException {
        if (offset < 0 || length < 0) throw new IllegalArgumentException("Invalid object range");
        if (!exists(user, asset)) throw new FileNotFoundException("Asset content is missing");
        InputStream stream = Files.newInputStream(path(user, asset), LinkOption.NOFOLLOW_LINKS);
        try {
            stream.skipNBytes(offset);
            return new AssetContentStream(stream, length);
        } catch (IOException | RuntimeException failure) {
            stream.close();
            throw failure;
        }
    }

    /** 删除指定本地资源文件；文件不存在时无需额外处理。 */
    @Override
    public void delete(UUID user, UUID asset) throws IOException { Files.deleteIfExists(path(user, asset)); }

    /** 只检查根目录下两级规范 UUID 文件，按年龄和业务保留回调决定清理。 */
    @Override
    public void cleanupOrphans(Duration age, BiPredicate<UUID, UUID> retain) throws IOException {
        if (age.isNegative()) throw new IllegalArgumentException("Age must not be negative");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        Instant cutoff = Instant.now().minus(age);
        try (Stream<Path> files = Files.walk(root, 2)) {
            for (Path file : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                if (!file.getParent().getParent().equals(root)
                        || !Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) continue;
                UUID owner, id;
                try {
                    owner = UUID.fromString(file.getParent().getFileName().toString());
                    id = UUID.fromString(file.getFileName().toString());
                    if (!owner.toString().equals(file.getParent().getFileName().toString())
                            || !id.toString().equals(file.getFileName().toString())) continue;
                } catch (IllegalArgumentException invalid) { continue; }
                if (!retain.test(owner, id)) Files.deleteIfExists(file);
            }
        }
    }

    /** 先检查文件存在和大小，再流式计算 SHA-256，验证内容没有损坏或被替换。 */
    public boolean matches(UUID userId, UUID assetId, long expectedSize, String expectedSha256) throws IOException {
        return matches(userId, assetId, expectedSize, expectedSha256, TransferDeadline.afterMillis(180_000));
    }

    @Override
    public boolean matches(UUID userId, UUID assetId, long expectedSize, String expectedSha256,
                           TransferDeadline deadline) throws IOException {
        deadline.check();
        Path file = path(userId, assetId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) != expectedSize) {
            return false;
        }
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                deadline.check();
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).equals(expectedSha256);
    }

    /** 识别本组件命名的过期临时文件，并在同步边界内排除正在写入的文件。 */
    public int cleanupTemporaryFiles(Duration minimumAge) throws IOException {
        Objects.requireNonNull(minimumAge, "minimumAge");
        if (minimumAge.isNegative()) {
            throw new IllegalArgumentException("minimumAge must not be negative");
        }
        if (!Files.exists(root)) {
            return 0;
        }

        Instant cutoff = Instant.now().minus(minimumAge);
        int deleted = 0;
        try (Stream<Path> files = Files.walk(root, 2)) {
            for (Path file : files.filter(this::isTemporaryFile).toList()) {
                synchronized (activeTemporaryFiles) {
                    if (!activeTemporaryFiles.contains(file)
                            && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)
                            && Files.deleteIfExists(file)) {
                        deleted++;
                    }
                }
            }
        }
        return deleted;
    }

    /** 创建或校验私有目录；POSIX 文件系统下将权限限制为当前用户可读写和进入。 */
    static void ensurePrivateDirectory(Path directory) throws IOException {
        boolean exists = Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
        if (exists && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Asset storage path is not a directory: " + directory);
        }
        Path filesystemPath = exists ? directory : directory.getParent();
        boolean posix = Files.getFileStore(filesystemPath).supportsFileAttributeView("posix");
        try {
            if (posix) {
                Files.createDirectory(directory,
                        PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
            } else {
                Files.createDirectory(directory);
            }
        } catch (FileAlreadyExistsException ignored) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Asset storage path is not a directory: " + directory);
            }
        }
        if (posix) {
            Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
        }
    }

    /** 检查临时文件命名、目录层级和 UUID 形状，避免误删无关文件。 */
    private boolean isTemporaryFile(Path file) {
        String name = file.getFileName().toString();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.getParent().getParent().equals(root)
                || name.length() < 42
                || name.charAt(36) != '-'
                || !name.endsWith(".tmp")) {
            return false;
        }
        try {
            UUID.fromString(name.substring(0, 36));
            UUID.fromString(file.getParent().getFileName().toString());
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** 为一次传输或校验创建独立的 SHA-256 摘要实例。 */
    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

}
