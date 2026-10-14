package com.lux.backend.asset;

import com.lux.backend.common.Timestamps;
import com.lux.backend.common.ApiException;
import com.lux.backend.common.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 上传业务服务：校验文件信息、存储字节、核对幂等请求并提交元数据。
 * 文件传输位于数据库重试事务之外，避免并发冲突重试时重复执行外部 I/O。
 */
@Service
public class AssetService {
    /** 资源元数据仓储，用于归属校验、结果查询和持久化。 */
    private final AssetRepository assets;
    /** 资源字节存储接口，隔离业务元数据与实际文件传输。 */
    private final AssetStore store;
    /** 数据库事务执行边界；默认注入支持锁冲突重试的 DatabaseTransactions。 */
    private final TransactionTemplate transactions;
    /** 单个上传或下载资源的字节上限，由 lux.storage.max-bytes 配置。 */
    private final long maxBytes;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public AssetService(AssetRepository assets, AssetStore store, TransactionTemplate transactions,
                        @Value("${lux.storage.max-bytes:104857600}") long maxBytes) {
        this.assets = assets;
        this.store = store;
        this.transactions = transactions;
        this.maxBytes = maxBytes;
    }

    /** 先流式保存并计算摘要，再在可重试事务内校验上传幂等键并保存元数据。 */
    public AssetRecord upload(String ownerId, MultipartFile file, String operationId) {
        ownerId = Ids.require(ownerId);
        operationId = Ids.require(operationId);
        if (file == null) {
            throw ApiException.badRequest("File is required");
        }
        String filename = filename(file.getOriginalFilename());
        String mediaType = mediaType(file.getContentType());
        String id = Ids.next();
        UUID ownerUuid = UUID.fromString(ownerId);
        UUID assetUuid = UUID.fromString(id);
        boolean written = false;
        try {
            AssetStore.StoredFile stored;
            // 先把字节保存到本次新资源 ID 下并得到真实摘要；数据库重试只复用这份已存内容。
            try (InputStream input = file.getInputStream()) {
                stored = store.write(ownerUuid, assetUuid, input, maxBytes);
            }
            written = true;
            if (stored.sizeBytes() == 0) {
                throw ApiException.badRequest("File must not be empty");
            }
            AssetRecord proposed = new AssetRecord(id, ownerId, null, filename, mediaType,
                    stored.sizeBytes(), stored.sha256(), Timestamps.now());
            String owner = ownerId;
            String key = operationId;
            AssetRecord result = Objects.requireNonNull(transactions.execute(status -> {
                assets.coordinateOwner(owner);
                Optional<AssetRecord> existing = assets.findByOperation(owner, key);
                if (existing.isPresent()) {
                    AssetRecord record = existing.get();
                    if (!sameUpload(record, proposed)) {
                        throw ApiException.conflict("Idempotency key belongs to a different upload");
                    }
                    return record;
                }
                assets.insert(proposed, key, null);
                return proposed;
            }));
            // 幂等重试复用了旧资源，删除的只是本次额外上传的新对象，不是原资源。
            if (!result.id().equals(id)) {
                deleteNewAsset(ownerUuid, assetUuid);
            }
            return result;
        } catch (AssetStore.AssetTooLargeException exception) {
            throw new ApiException(413, "ASSET_TOO_LARGE", exception.getMessage());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not store asset", exception);
        } catch (RuntimeException exception) {
            if (written) {
                try {
                    deleteNewAsset(ownerUuid, assetUuid);
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    /** 规范化用户和资源 UUID，返回有归属约束的元数据。 */
    public AssetRecord get(String ownerId, String id) {
        return assets.get(Ids.require(ownerId), Ids.require(id));
    }

    /** 同时比较文件名、媒体类型、长度和摘要，识别同一幂等键是否被用于不同上传。 */
    private static boolean sameUpload(AssetRecord existing, AssetRecord proposed) {
        return existing.filename().equals(proposed.filename())
                && existing.mediaType().equals(proposed.mediaType())
                && existing.sizeBytes() == proposed.sizeBytes()
                && existing.sha256().equals(proposed.sha256());
    }

    /** 去掉客户端文件路径并校验文件名，元数据只保留可展示的名称。 */
    private static String filename(String original) {
        if (original == null) {
            throw ApiException.badRequest("Filename is required");
        }
        String normalized = original.replace('\\', '/');
        String result = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (result.isEmpty() || result.length() > 255 || result.chars().anyMatch(c -> Character.isISOControl((char) c))) {
            throw ApiException.badRequest("Invalid filename");
        }
        return result;
    }

    /** 解析并要求具体 MIME 类型，拒绝通配类型和格式错误。 */
    private static String mediaType(String raw) {
        if (raw == null || raw.length() > 128) {
            throw ApiException.badRequest("A valid media type is required");
        }
        try {
            MediaType parsed = MediaType.parseMediaType(raw);
            if (parsed.isWildcardType() || parsed.isWildcardSubtype()) {
                throw ApiException.badRequest("A concrete media type is required");
            }
            return parsed.toString();
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("A valid media type is required");
        }
    }

    /** 清理本次上传产生但未被选用的对象，供幂等复用或失败补偿使用。 */
    private void deleteNewAsset(UUID ownerId, UUID assetId) {
        try {
            store.delete(ownerId, assetId);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not remove unused asset", exception);
        }
    }
}
