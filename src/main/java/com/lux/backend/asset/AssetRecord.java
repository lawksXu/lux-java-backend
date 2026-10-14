package com.lux.backend.asset;

import java.time.Instant;

/**
 * 资源元数据快照，用于服务间传递及 HTTP 返回；不暴露存储凭据或对象存储地址。
 * @param id 资源的唯一 UUID 身份。
 * @param userId 所属用户 ID，用于标识业务归属与限定资源访问范围。
 * @param taskId 关联的生成任务 ID；普通上传资源中为 null。
 * @param filename 供下载和展示使用的文件名，不作为底层存储路径。
 * @param mediaType 资源的具体 MIME 类型，例如 image/png 或 video/mp4。
 * @param sizeBytes 实际内容长度，单位为字节。
 * @param sha256 实际文件内容的 SHA-256 十六进制摘要，用于幂等与完整性校验。
 * @param createdAt 记录创建时间，持久化精度为微秒。
 */
public record AssetRecord(String id, String userId, String taskId, String filename,
                          String mediaType, long sizeBytes, String sha256, Instant createdAt) { }
