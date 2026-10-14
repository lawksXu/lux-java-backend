/**
 * 资源组件：分离用户归属与元数据管理、文件字节存储以及 HTTP 下载协议。
 *
 * AssetService/AssetRepository 管理上传幂等和数据库记录；AssetStore 隔离本地磁盘与 S3。
 * AssetContentResponse 处理完整与 Range 下载；AssetMaintenance 结合数据库引用保护导入中的文件。
 * 文件传输和摘要计算处于数据库重试回调之外，避免一次锁冲突触发重复传输。
 */
package com.lux.backend.asset;
