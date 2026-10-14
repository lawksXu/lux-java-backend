package com.lux.backend.asset;

import com.lux.backend.common.ApiException;
import com.lux.backend.user.UserRepository;
import com.lux.backend.chat.ConversationContent.TaskAssetReference;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import com.lux.backend.generation.GenerationPlanContract.VerifiedMediaMetadata;
import com.lux.backend.generation.GenerationPlanCodec;

/** 资源元数据访问边界：封装用户范围查询、上传幂等查询和用户级写入协调。 */
@Repository
@Transactional(readOnly = true)
public class AssetRepository {
    /** Spring Data 资源元数据访问接口，封装带用户约束的查询。 */
    private final AssetJpaRepository assets;
    /** 用户持久化服务，提供查询、初始化和用户级写入协调。 */
    private final UserRepository users;
    /** JPA 持久化上下文入口，管理实体加载、flush 同步和 refresh 重读。 */
    private final EntityManager entityManager;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public AssetRepository(AssetJpaRepository assets, UserRepository users, EntityManager entityManager) {
        this.assets = assets;
        this.users = users;
        this.entityManager = entityManager;
    }

    /** 按用户归属查找资源快照，不存在时返回空结果。 */
    public Optional<AssetRecord> find(String ownerId, String id) {
        return assets.findByUserIdAndId(ownerId, id).map(AssetEntity::toRecord);
    }

    /** 读取用户资源，缺失或归属不匹配时统一返回 404。 */
    public AssetRecord get(String ownerId, String id) {
        return find(ownerId, id).orElseThrow(ApiException::notFound);
    }

    /** 内容检查缓存只在 checksum 一致时有效，读取不信任上传 MIME 或模型提供的事实。 */
    public Optional<VerifiedMediaMetadata> inspected(String owner, String id) {
        return assets.findByUserIdAndId(owner, id).flatMap(row -> {
            if (row.verifiedMediaMetadata == null) return Optional.empty();
            VerifiedMediaMetadata fact = new GenerationPlanCodec().read(row.verifiedMediaMetadata, VerifiedMediaMetadata.class);
            String checksum = fact instanceof com.lux.backend.generation.GenerationPlanContract.VerifiedImageMetadata image ? image.sha256()
                : fact instanceof com.lux.backend.generation.GenerationPlanContract.VerifiedVideoMetadata video ? video.sha256()
                : ((com.lux.backend.generation.GenerationPlanContract.VerifiedAudioMetadata) fact).sha256();
            return row.sha256.equals(checksum) ? Optional.of(fact) : Optional.empty();
        });
    }

    /** 检查在事务外完成；短事务再核对不可变资源摘要后才保存具名事实。 */
    @Transactional
    public void saveInspection(String owner, String id, String expectedChecksum, VerifiedMediaMetadata fact) {
        AssetEntity row = assets.findByUserIdAndId(owner, id).orElseThrow(ApiException::notFound);
        if (!row.sha256.equals(expectedChecksum)) throw ApiException.conflict("Asset changed during inspection");
        row.verifiedMediaMetadata = new GenerationPlanCodec().write(fact);
        entityManager.flush();
    }

    /** 查找同一用户已提交的上传操作，恢复其原始资源记录。 */
    public Optional<AssetRecord> findByOperation(String ownerId, String operationId) {
        return assets.findByUserIdAndOperationId(ownerId, operationId).map(AssetEntity::toRecord);
    }

    /** 按结果来源顺序返回某个任务的所有资源。 */
    public List<AssetRecord> forTask(String ownerId, String taskId) {
        return assets.findByUserIdAndTaskIdOrderBySourceIndexAscCreatedAtAscIdAsc(ownerId, taskId).stream()
                .map(AssetEntity::toRecord).toList();
    }

    /** 返回真实来源下标；部分结果不能用查询列表位置冒充 source_index。 */
    public List<TaskAssetReference> taskReferences(String ownerId, String taskId) {
        return assets.findByUserIdAndTaskIdOrderBySourceIndexAscCreatedAtAscIdAsc(ownerId, taskId).stream()
                .map(asset -> new TaskAssetReference(asset.id, asset.sourceIndex)).toList();
    }

    /** 检查元数据是否仍引用此存储对象。 */
    public boolean exists(String ownerId, String id) {
        return assets.existsByUserIdAndId(ownerId, id);
    }

    /** 在调用者事务中协调用户级写入；需要会话协调时必须先取得这一边界。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void coordinateOwner(String ownerId) {
        users.coordinate(ownerId);
    }

    /** 写入资源元数据并 flush，及早触发约束检查；事务提交仍由外层决定。 */
    @Transactional
    public void insert(AssetRecord asset, String operationId, Integer sourceIndex) {
        entityManager.persist(new AssetEntity(asset, operationId, sourceIndex));
        entityManager.flush();
    }
}
