package com.lux.backend.asset;

import com.lux.backend.task.TaskRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/** 资源维护组件：清理过期临时文件，并结合数据库记录保护仍被引用或正在导入的对象。 */
@Component
public class AssetMaintenance {
    /** 资源字节存储接口，隔离业务元数据与实际文件传输。 */
    private final AssetStore store;
    /** 资源元数据仓储，用于归属校验、结果查询和持久化。 */
    private final AssetRepository assets;
    /** 生成任务仓储，用未终结导入状态保护尚未登记元数据的对象。 */
    private final TaskRepository tasks;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public AssetMaintenance(
        AssetStore store,
        AssetRepository assets,
        TaskRepository tasks
    ) {
        this.store = store;
        this.assets = assets;
        this.tasks = tasks;
    }

    /** 删除超过一天的闲置临时文件，并筛选超过两天且无需保留的孤立对象。 */
    public void cleanup() throws IOException {
        store.cleanupTemporaryFiles(Duration.ofDays(1));
        // 导入可能先写稳定对象键，后提交 asset 记录；用户仍有 IMPORTING 任务时保守保留候选对象。
        store.cleanupOrphans(Duration.ofDays(2), (owner, id) ->
                assets.exists(owner.toString(), id.toString()) || tasks.hasImporting(owner.toString()));
    }
}
