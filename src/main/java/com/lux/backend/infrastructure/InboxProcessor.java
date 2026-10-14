package com.lux.backend.infrastructure;

import com.lux.backend.chat.RunEventService;
import com.lux.backend.common.ApiException;
import com.lux.backend.common.Ids;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.task.TaskResultService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 可靠接收处理器：先把 Kafka 记录持久化，再校验协议并应用业务变更。
 * 业务处理与 APPLIED 标记处于同一事务；失败后区分永久拒绝和可重试错误。
 */
@Service
public class InboxProcessor {
    /**
     * 可靠接收仓储，保存入站原文、去重证据和处理状态。
     */
    private final InboxRepository inbox;
    /**
     * Agent 事件应用服务，维护 Run 状态和助手消息。
     */
    private final RunEventService runs;
    /**
     * 生成任务业务服务，负责创建、供应商事件应用和状态同步。
     */
    private final TaskResultService results;
    /**
     * 数据库事务执行边界；默认注入支持锁冲突重试的 DatabaseTransactions。
     */
    private final TransactionTemplate transactions;
    /**
     * 统一 JSON 编解码器，负责载荷构建、解析与规范化摘要。
     */
    private final JsonCodec json;

    /**
     * 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。
     */
    public InboxProcessor(
        InboxRepository inbox,
        RunEventService runs,
        TaskResultService results,
        TransactionTemplate transactions,
        JsonCodec json
    ) {
        this.inbox = inbox;
        this.runs = runs;
        this.results = results;
        this.transactions = transactions;
        this.json = json;
    }

    /**
     * 解析入站记录并校验分区键，保存原始信封后立即尝试业务处理；坏消息也保留诊断身份。
     */
    public void receive(
        String source,
        String key,
        String raw,
        int partition,
        long offset
    ) {
        JsonNode event;
        // 处理无法解析的 json string
        try {
            event = json.read(raw);
        } catch (RuntimeException invalid) {
            event = json.object().put("invalid_json", raw);
        }
        if (event == null) {
            event = json.object().putNull("invalid_json");
        }
        String id;
        try {
            // 正常情况下使用 `event_id` 作为 unique key 进行幂等去重
            // 如果无法提取，则使用 catch 中的代码生成一个
            // 重点在于一致性
            id = Ids.require(event.path("event_id").asString());
        } catch (RuntimeException invalid) {
            // 坏事件也需要稳定诊断身份；用 topic/partition/offset 生成 UUID，使同一 Kafka 记录重试时可定位。
            id = UUID
                .nameUUIDFromBytes((source + ":" + partition + ":" + offset).getBytes(StandardCharsets.UTF_8))
                .toString();
        }
        String owner = event.path("user_id").asString("unknown");
        if (owner.length() > 128) {
            owner = "invalid";
        }
        String expected = event.path("user_id").asString() + ":" +
                          event.path(Topics.AGENT_EVENTS.equals(source) ? "session_id" : "task_id").asString();
        // Agent 事件按用户/会话分区，生成事件按用户/任务分区；错键另存诊断记录，不影响同 ID 的合法事件。
        if (!expected.equals(key)) {
            String diagnosticId = UUID.nameUUIDFromBytes((source + ":" + partition + ":" + offset + ":invalid-key")
                                                             .getBytes(StandardCharsets.UTF_8)).toString();
            inbox.receiveRejected(source, diagnosticId, owner, event, "Partition key does not match resource identity");
            return;
        }
        // 接收入库先独立提交；生成事件仍须完成导入才允许监听器正常返回并确认 offset。
        if (!inbox.receive(source, id, owner, event)) {
            return;
        }
        process(source, id);
    }

    /**
     * 扫描到期 Inbox 记录，用于恢复暂时失败或进程中断留下的工作。
     */
    public void drain() {
        for (InboxRepository.Key key : inbox.pending()) {
            if (!Topics.generationEvents(key.source())) process(key.source(), key.id());
        }
    }

    /**
     * 在同一事务中完成协议校验、业务状态变更和 APPLIED 标记，失败后另行记录拒绝或重试。
     */
    public void process(
        String source,
        String id
    ) {
        if (Topics.generationEvents(source)) {
            processGeneration(source, id);
            return;
        }
        try {
            // 业务更新和 APPLIED 标记一起提交；两者之间不会留下可见的半完成状态。
            transactions.executeWithoutResult(status -> {
                InboxRepository.Entry entry = inbox.forProcessing(source, id);
                if (!entry.status().equals("RECEIVED")) {
                    return;
                }
                if (source.equals(Topics.AGENT_EVENTS)) {
                    runs.applyEvent(EventProtocol.agent(entry.envelope()));
                } else {
                    throw new IllegalArgumentException("Unexpected event source");
                }
                inbox.applied(source, id);
            });
        } catch (IllegalArgumentException | ApiException | java.time.DateTimeException rejected) {
            inbox.rejected(source, id, rejected.getMessage());
        } catch (RuntimeException retry) {
            inbox.retry(source, id, retry.getClass().getSimpleName() + ": " + retry.getMessage());
        }
    }

    /**
     * 生成记录的读取协调是短事务，下载由接收线程完成；终态事务通过回调原子标记 APPLIED。
     * 技术故障必须继续抛给 Kafka，不能仅保存 retry 后正常返回，否则未完成的记录会被确认。
     */
    private void processGeneration(String source, String id) {
        try {
            InboxRepository.Entry entry = transactions.execute(status -> inbox.forProcessing(source, id));
            if (entry == null) throw new IllegalStateException("Generation receipt unavailable");
            if (!"RECEIVED".equals(entry.status())) return;
            IncomingEvent.Generation event = EventProtocol.generation(entry.envelope());
            int expectedVersion = Topics.LEGACY_GENERATION_EVENTS.equals(source) ? 1 : 2;
            if (event.header().schemaVersion() != expectedVersion) {
                throw new IllegalArgumentException("Generation topic/protocol version differs");
            }
            results.receive(event, () -> inbox.applied(source, id));
        } catch (IllegalArgumentException | ApiException | java.time.DateTimeException rejected) {
            // 拒绝诊断也必须先持久化；若数据库保存失败，其异常同样阻止 Kafka 确认。
            inbox.rejected(source, id, rejected.getMessage());
        } catch (RuntimeException retry) {
            try {
                inbox.retry(source, id, retry.getClass().getSimpleName() + ": " + retry.getMessage());
            } catch (RuntimeException diagnosticFailure) {
                retry.addSuppressed(diagnosticFailure);
            }
            throw retry;
        }
    }
}
