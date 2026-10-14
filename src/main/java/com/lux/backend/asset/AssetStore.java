package com.lux.backend.asset;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * 资源字节存储契约，屏蔽本地文件与 S3 的差异；调用方负责先校验资源归属。
 * 此接口处理传输与清理，不负责数据库元数据和业务事务。
 */
public interface AssetStore {
    /** 在字节上限内写入资源，返回实际长度和 SHA-256；不提交任何数据库记录。 */
    StoredFile write(UUID user, UUID asset, InputStream input, long maxBytes) throws IOException;
    /** 导入使用统一单调时限，暂存和对象发布都不能脱离本次回调预算。 */
    StoredFile write(UUID user, UUID asset, InputStream input, long maxBytes, TransferDeadline deadline) throws IOException;
    /** 先验证内容与已保存的长度和摘要一致，再发布到原资源身份，防止重试改变既有结果。 */
    StoredFile writeVerified(UUID user, UUID asset, InputStream input, long maxBytes,
                             long expectedSize, String expectedSha256) throws IOException;
    /** 打开从 offset 开始、长度为 length 的流；返回流由调用者关闭。 */
    InputStream open(UUID user, UUID asset, long offset, long length) throws IOException;
    /** 检查内容是否存在；连接或权限失败应作为错误传播。 */
    boolean exists(UUID user, UUID asset) throws IOException;
    /** 读取并校验内容长度与摘要，确认存储字节和数据库元数据一致。 */
    boolean matches(UUID user, UUID asset, long size, String sha256) throws IOException;
    /** 在同一导入预算内实际校验字节，不仅信任对象存储的摘要元数据。 */
    boolean matches(UUID user, UUID asset, long size, String sha256, TransferDeadline deadline) throws IOException;
    /** 删除指定身份的存储对象；元数据的生命周期由业务层管理。 */
    void delete(UUID user, UUID asset) throws IOException;
    /** 清理超过指定时间且未被当前进程使用的暂存文件，返回删除数量。 */
    int cleanupTemporaryFiles(Duration age) throws IOException;
    /** 筛选达到保留期限的对象，并只删除 retain 回调判定无需保留的对象。 */
    void cleanupOrphans(Duration age, BiPredicate<UUID, UUID> retain) throws IOException;

    /**
     * 写入成功后的内容摘要，供数据库记录和后续完整性校验使用。
     * @param sizeBytes 实际内容长度，单位为字节。
     * @param sha256 实际文件内容的 SHA-256 十六进制摘要，用于幂等与完整性校验。
     */
    record StoredFile(long sizeBytes, String sha256) { }

    /** 进程内的单调时限，不写入 Task 或数据库；中断保留原操作的恢复资格。 */
    record TransferDeadline(long expiresAtNanos) {
        public static TransferDeadline afterMillis(long timeoutMillis) {
            if (timeoutMillis < 1) throw new IllegalArgumentException("Transfer timeout must be positive");
            return new TransferDeadline(System.nanoTime() + Math.multiplyExact(timeoutMillis, 1_000_000));
        }
        public void check() throws TransferInterruptedException {
            if (Thread.currentThread().isInterrupted()) throw new TransferInterruptedException("Asset transfer interrupted");
            if (expiresAtNanos - System.nanoTime() <= 0) throw new TransferInterruptedException("Asset transfer budget exhausted");
        }
        public Duration remaining() throws TransferInterruptedException {
            check();
            return Duration.ofNanos(Math.max(1, expiresAtNanos - System.nanoTime()));
        }
    }

    /** 已发布的稳定资源身份不能被不同字节覆盖；调用方可记录确认的导入失败。 */
    class AssetConflictException extends IOException {
        public AssetConflictException(String message) { super(message); }
    }

    /** 时限或线程中断属于技术恢复，不能自动解释为生成或导入业务失败。 */
    class TransferInterruptedException extends IOException {
        public TransferInterruptedException(String message) { super(message); }
    }

    /** 流式写入超过字节上限时抛出的异常，业务层可将其转换为 HTTP 413。 */
    class AssetTooLargeException extends IOException {
        /** 把配置的字节上限带入错误说明，供上传接口反馈。 */
        public AssetTooLargeException(long maxBytes) { super("Asset exceeds " + maxBytes + " bytes"); }
    }
}
