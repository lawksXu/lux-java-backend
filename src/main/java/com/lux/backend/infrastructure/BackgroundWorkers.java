package com.lux.backend.infrastructure;

import com.lux.backend.asset.AssetMaintenance;
import com.lux.backend.task.TaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 后台任务入口，定时执行 Outbox、非生成 Inbox 恢复、来源观察和存储维护。
 * 受 lux.workers.enabled 控制；关闭这些定时任务不会关闭 Kafka 监听器。
 */
@Component
@ConditionalOnProperty(name = "lux.workers.enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundWorkers {
    /** Kafka 发布器，发送 Outbox 记录并保存成功或重试结果。 */
    private final OutboxPublisher outbox;
    /** 入站事件处理器，负责协议校验、业务应用和失败分类。 */
    private final InboxProcessor inbox;
    /** Session 来源观察组件，修复遗漏事实而不调度执行。 */
    private final SessionExecutionRecovery sessions;
    /** 生成任务业务服务，负责创建、供应商事件应用和状态同步。 */
    private final TaskService tasks;
    /** 存储维护组件，结合业务引用清理临时文件和孤立对象。 */
    private final AssetMaintenance storage;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public BackgroundWorkers(
        OutboxPublisher outbox,
        InboxProcessor inbox,
        SessionExecutionRecovery sessions,
        TaskService tasks,
        AssetMaintenance storage
    ) {
        this.outbox = outbox;
        this.inbox = inbox;
        this.sessions = sessions;
        this.tasks = tasks;
        this.storage = storage;
    }

    /** 上一轮完成后等待 500 毫秒，再发布待发送的 Outbox 命令。 */
    @Scheduled(fixedDelay = 500)
    public void publish() {outbox.publishPending();}

    /** 每轮间隔 500 毫秒，恢复处理到期的 Inbox 记录。 */
    @Scheduled(fixedDelay = 500)
    public void receive() {inbox.drain();}

    /** 每轮间隔 30 秒，读取 Session 来源投影并查询尚未确认的生成任务。 */
    @Scheduled(fixedDelay = 30000)
    public void reconcile() {
        sessions.reconcileStale();
        tasks.reconcileStale();
    }

    /** 每轮间隔一小时，执行存储清理与业务保留检查。 */
    @Scheduled(fixedDelay = 3600000)
    public void cleanup() throws IOException {storage.cleanup();}
}
