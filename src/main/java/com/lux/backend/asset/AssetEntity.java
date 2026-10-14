package com.lux.backend.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * asset 表的 JPA 映射；只保存资源元数据，文件字节存放在 AssetStore。
 * @Immutable 表示已有元数据不通过实体脏检查更新；更换内容应使用新的资源身份。
 */
@Entity
@Immutable
@Table(name = "asset")
public class AssetEntity {
    /** 资源的唯一 UUID 身份。 */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36, nullable = false, columnDefinition = "char(36)")
    String id;

    /** 所属用户 ID，用于标识业务归属与限定资源访问范围。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false, columnDefinition = "char(36)")
    String userId;

    /** 关联的生成任务 ID；普通上传资源中为 null。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "task_id", length = 36, columnDefinition = "char(36)")
    String taskId;

    /** 资源在任务结果列表中的下标，用于稳定排序；普通上传时为空。 */
    @Column(name = "source_index")
    Integer sourceIndex;

    /** 调用方提供的操作 UUID；同一用户重试同一次操作时保持不变。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "operation_id", length = 36, columnDefinition = "char(36)")
    String operationId;

    /** 供下载和展示使用的文件名，不作为底层存储路径。 */
    @Column(name = "filename", length = 255, nullable = false)
    String filename;

    /** 资源的具体 MIME 类型，例如 image/png 或 video/mp4。 */
    @Column(name = "media_type", length = 128, nullable = false)
    String mediaType;

    /** 实际内容长度，单位为字节。 */
    @Column(name = "size_bytes", nullable = false)
    long sizeBytes;

    /** 实际文件内容的 SHA-256 十六进制摘要，用于幂等与完整性校验。 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64, nullable = false, columnDefinition = "char(64)")
    String sha256;

    /** 可信内容检查产生的具名媒体事实；绑定实际校验和，上传声明不能直接写入。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verified_media_metadata", columnDefinition = "json")
    String verifiedMediaMetadata;

    /** 记录创建时间，持久化精度为微秒。 */
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    Instant createdAt;

    /** 供 JPA 通过反射创建实体，字段由持久化映射填充。 */
    protected AssetEntity() {}

    /** 将资源快照和幂等/来源标识映射为待插入的实体。 */
    AssetEntity(
        AssetRecord asset,
        String operationId,
        Integer sourceIndex
    ) {
        this.id = asset.id();
        this.userId = asset.userId();
        this.taskId = asset.taskId();
        this.sourceIndex = sourceIndex;
        this.operationId = operationId;
        this.filename = asset.filename();
        this.mediaType = asset.mediaType();
        this.sizeBytes = asset.sizeBytes();
        this.sha256 = asset.sha256();
        this.createdAt = asset.createdAt();
    }

    /** 复制实体元数据为 record；调用者不需要依赖 JPA 生命周期。 */
    AssetRecord toRecord() {
        return new AssetRecord(id, userId, taskId, filename, mediaType, sizeBytes, sha256, createdAt);
    }
}
