package com.lux.backend.task;

import com.lux.backend.common.ApiException;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.common.Timestamps;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.Instant;

/** Session Task 持久化；固定请求直接比较，导入没有租约或业务重试。 */
@Repository
@Transactional(readOnly = true)
public class TaskRepository {
    /** 用户范围的 Task 查询及生成前段只读修复候选。 */
    private final TaskJpaRepository rows;
    /** 短事务行锁、版本协调和显式 flush 的持久化上下文。 */
    private final EntityManager entities;
    /** 仅存储原请求和固定清单，provider 参数结构由 connector 所有。 */
    private final JsonCodec json;

    public TaskRepository(TaskJpaRepository rows, EntityManager entities, JsonCodec json) {
        this.rows = rows;
        this.entities = entities;
        this.json = json;
    }

    public Optional<TaskRecord> findByOperation(String owner, String operationId) {
        return rows.findByUserIdAndOperationId(owner, operationId).map(this::record);
    }

    public TaskRecord get(String owner, String id) { return record(entity(owner, id)); }

    /** Session 锁后重读 Task，只在短事务内保持行锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public TaskRecord getForMutation(String owner, String id) {
        TaskEntity task = entity(owner, id);
        entities.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        return record(task);
    }

    public List<TaskRecord> batch(String owner, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        Map<String, TaskEntity> found = rows.findByUserIdAndIdIn(owner, ids).stream()
                .collect(Collectors.toMap(task -> task.id, Function.identity()));
        return ids.stream().map(id -> record(Optional.ofNullable(found.get(id)).orElseThrow(ApiException::notFound))).toList();
    }

    /** 新请求和提交 Outbox 由服务在同一事务保存。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(TaskRecord task) {
        TaskEntity row = new TaskEntity();
        row.id = task.id();
        row.userId = task.userId();
        row.sessionId = task.sessionId();
        row.planId = task.planId();
        row.operationId = task.operationId();
        row.fileType = task.fileType();
        row.payload = json.write(task.payload());
        row.status = "CREATED";
        row.createdAt = task.createdAt();
        row.updatedAt = task.updatedAt();
        entities.persist(row);
        entities.flush();
    }

    /** 来源清单首次接收后不再替换；进度只增加。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void observed(String owner, String id, String status, long sequence, List<ResultSource> sources) {
        TaskEntity row = entity(owner, id);
        row.status = status;
        row.providerEventSeq = Math.max(row.providerEventSeq, sequence);
        if (sources != null && row.resultSources == null) row.resultSources = json.write(sources);
        entities.flush();
    }

    /** 终态条件、行锁及 ORM 版本一起防止旧 worker 反转结果。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean finish(String owner, String id, String status, String stage, String code, String message) {
        TaskEntity row = entity(owner, id);
        if (row.status.equals("SUCCEEDED") || row.status.equals("FAILED")) return false;
        row.status = status;
        row.errorStage = stage;
        row.errorCode = code;
        row.errorMessage = message;
        row.finishedAt = Timestamps.now();
        entities.flush();
        return true;
    }

    /** 新 Asset 改变可查询的 Task 投影，来源元数据与版本推进必须同事务提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assetRegistered(String owner, String id) {
        entities.lock(entity(owner, id), LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    public List<TaskRecord> staleTasks() {
        return rows.staleTasks(Timestamps.now().minusSeconds(60), PageRequest.of(0, 100)).stream().map(this::record).toList();
    }

    /** 游标只指导只读观察，原 Task 时间和供应商进度不因查询而改变。 */
    public List<TaskRecord> staleTasks(ObservationCursor after) {
        if (after == null) return staleTasks();
        return rows.staleTasksAfter(Timestamps.now().minusSeconds(60), after.updatedAt(), after.id(),
                PageRequest.of(0, 100)).stream().map(this::record).toList();
    }

    /** 服务内 advisory 位置；进程重启从首段开始，不持久化成业务状态。 */
    public record ObservationCursor(Instant updatedAt, String id) { }

    /** 清理保留尚未提交 Asset 元数据的稳定对象。 */
    public boolean hasImporting(String owner) { return rows.existsByUserIdAndStatus(owner, "IMPORTING"); }

    private TaskEntity entity(String owner, String id) {
        return rows.findByUserIdAndId(owner, id).orElseThrow(ApiException::notFound);
    }

    private TaskRecord record(TaskEntity row) {
        return new TaskRecord(row.id, row.userId, row.sessionId, row.operationId, row.fileType,
                json.read(row.payload, ConnectorRequest.class), row.status, row.version, row.providerEventSeq,
                row.resultSources == null ? null : json.readList(row.resultSources, ResultSource.class),
                row.errorStage, row.errorCode, row.errorMessage, row.finishedAt, row.createdAt, row.updatedAt, row.planId);
    }
}
