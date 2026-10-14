package com.lux.backend.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 资源元数据的 Spring Data 查询接口；方法名同时约束用户归属、查询条件和结果顺序。 */
public interface AssetJpaRepository extends JpaRepository<AssetEntity, String> {
    /** 按用户和资源 ID 查询，避免跨用户读取元数据。 */
    Optional<AssetEntity> findByUserIdAndId(
        String userId,
        String id
    );

    /** 按用户和上传操作 ID 查找已有资源，用于幂等重放。 */
    Optional<AssetEntity> findByUserIdAndOperationId(
        String userId,
        String operationId
    );

    /** 按供应商结果序号优先排列任务资源，并以创建时间和 ID 稳定排序。 */
    List<AssetEntity> findByUserIdAndTaskIdOrderBySourceIndexAscCreatedAtAscIdAsc(
        String userId,
        String taskId
    );

    /** 只检查当前用户的资源记录是否存在，供清理保留判断使用。 */
    boolean existsByUserIdAndId(
        String userId,
        String id
    );
}
