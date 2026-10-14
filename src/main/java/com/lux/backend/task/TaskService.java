package com.lux.backend.task;

import com.lux.backend.asset.AssetRecord;
import com.lux.backend.asset.AssetRepository;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.chat.ConversationContent.TaskFailure;
import com.lux.backend.chat.ConversationContent.TaskQuerySnapshot;
import com.lux.backend.chat.ConversationContent.TaskSubmissionReceipt;
import com.lux.backend.chat.SessionService;
import com.lux.backend.common.ApiException;
import com.lux.backend.common.Ids;
import com.lux.backend.common.Timestamps;
import com.lux.backend.infrastructure.CommandPayload;
import com.lux.backend.infrastructure.IncomingEvent;
import com.lux.backend.infrastructure.OutboxRepository;
import com.lux.backend.infrastructure.Topics;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.PropertyNamingStrategies;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.lux.backend.generation.GenerationPlanContract.*;

/** Session 任务受理、状态转换及终态输入；不读取 Java Run 执行状态，也不覆盖旧工具结果。 */
@Service
public class TaskService {
    /** 固定请求、连接器进度及不可逆 Task 终态的持久化边界。 */
    private final TaskRepository tasks;
    /** 只协调 Session 归属和短事务锁，不读取执行中的 Run。 */
    private final ChatRepository chats;
    /** 核验输入资源归属以及导入元数据和真实 source_index。 */
    private final AssetRepository assets;
    /** 与 Task 受理原子保存稳定生成命令，不直接调用 provider。 */
    private final OutboxRepository outbox;
    /** Task 终态共用 Session 输入路径，进度只更新前端。 */
    private final SessionService sessions;
    /** 数据库技术重试边界，任何外部 I/O 均由 TaskResultService 放在其外。 */
    private final TransactionTemplate transactions;
    /** 只读生成观察的公平位置，不是 Task 导入租约、消费进度或新的业务时间。 */
    private TaskRepository.ObservationCursor observationCursor;

    public TaskService(TaskRepository tasks, ChatRepository chats, AssetRepository assets,
                       OutboxRepository outbox, SessionService sessions, TransactionTemplate transactions) {
        this.tasks = tasks;
        this.chats = chats;
        this.assets = assets;
        this.outbox = outbox;
        this.sessions = sessions;
        this.transactions = transactions;
    }

    /** 操作幂等只比较原 Session/类别/参数；唯一约束竞争后读取赢家而不是另发提交。 */
    public TaskSubmissionReceipt createSessionTask(String owner, CreateSessionTaskRequest request) {
        String user = Ids.require(owner);
        if (request == null || request.request() == null) throw ApiException.badRequest("Task request is required");
        String operation = Ids.require(request.operationId());
        String session = Ids.require(request.sessionId());
        if (request.kind() == null || !List.of("IMAGE", "VIDEO").contains(request.kind())) throw ApiException.badRequest("Invalid generation kind");
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                Optional<TaskRecord> original = tasks.findByOperation(user, operation);
                if (original.isPresent()) return matching(original.get(), request);
                // 规范化请求只能经计划冻结后受理，旧入口仍允许读取既有操作回执。
                if (request.request().normalized() != null) {
                    throw ApiException.badRequest("Normalized generation requires a generation plan");
                }
                assets.coordinateOwner(user);
                chats.sessionForMutation(user, session);
                Optional<TaskRecord> winner = tasks.findByOperation(user, operation);
                if (winner.isPresent()) return matching(winner.get(), request);
                for (String assetId : request.request().assetIds()) assets.get(user, Ids.require(assetId));
                Instant now = Timestamps.now();
                String taskId = Ids.next();
                tasks.insert(new TaskRecord(taskId, user, session, operation, request.kind(), request.request(),
                        "CREATED", 1, 0, null, null, null, null, null, now, now));
                CommandPayload.GenerationSubmit command = new CommandPayload.GenerationSubmit(2, Ids.next(),
                        "SUBMIT", now.toString(), user, session, taskId, operation, request.kind(), request.request());
                outbox.enqueueEnvelope(Topics.GENERATION_COMMANDS, user + ":" + taskId, command);
                sessions.publishTaskInvalidation(user, session, taskId);
                return new TaskSubmissionReceipt(taskId, true);
            }));
        } catch (DataIntegrityViolationException conflict) {
            TaskRecord winner = tasks.findByOperation(user, operation).orElseThrow(() -> conflict);
            return matching(winner, request);
        }
    }

    /** 父计划事务创建独立子任务；本方法不重新分配身份、不调用供应商或自行提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public TaskRecord createPlanTask(String owner, String session, UUID planId, UUID taskId,
                                    UUID childOperationId, NormalizedGenerationRequest request) {
        ConnectorRequest payload = ConnectorRequest.normalized(request);
        for (String assetId : payload.assetIds()) assets.get(owner, assetId);
        String kind = request instanceof ImageGenerationRequest ? "IMAGE" : "VIDEO";
        Instant now = Timestamps.now();
        TaskRecord task = new TaskRecord(taskId.toString(), owner, session, childOperationId.toString(), kind, payload,
            "CREATED", 1, 0, null, null, null, null, null, now, now, planId.toString());
        tasks.insert(task);
        outbox.enqueueEnvelope(Topics.GENERATION_COMMANDS, owner + ":" + task.id(),
            new CommandPayload.GenerationSubmit(2, Ids.next(), "SUBMIT", now.toString(), owner, session,
                task.id(), task.operationId(), kind, payload));
        sessions.publishTaskInvalidation(owner, session, task.id());
        return task;
    }

    /** UNKNOWN 接收由原操作身份查询；404 不是再次付费提交的许可。 */
    @Transactional(readOnly = true)
    public TaskSubmissionReceipt receiptByOperation(String owner, String operationId) {
        TaskRecord task = tasks.findByOperation(Ids.require(owner), Ids.require(operationId)).orElseThrow(ApiException::notFound);
        return new TaskSubmissionReceipt(task.id(), true);
    }

    private TaskSubmissionReceipt matching(TaskRecord task, CreateSessionTaskRequest request) {
        if (!task.sessionId().equals(Ids.require(request.sessionId())) || !task.fileType().equals(request.kind())
                || !task.payload().equals(request.request())) throw ApiException.conflict("Operation belongs to a different task request");
        return new TaskSubmissionReceipt(task.id(), true);
    }

    /** 模型查询保持 Task 和真实来源下标在同一数据库快照中，不动态重写旧结果。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskQuerySnapshot snapshot(String owner, String sessionId, String taskId) {
        String user = Ids.require(owner);
        String session = Ids.require(sessionId);
        chats.session(user, session);
        TaskRecord task = tasks.get(user, Ids.require(taskId));
        if (!task.sessionId().equals(session)) throw ApiException.notFound();
        TaskFailure failure = task.status().equals("FAILED")
                ? new TaskFailure(task.errorStage(), task.errorCode(), task.errorMessage()) : null;
        return new TaskQuerySnapshot(task.id(), task.version(), task.status(), assets.taskReferences(user, task.id()), failure);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskView view(String owner, String taskId) { return viewOf(tasks.get(Ids.require(owner), Ids.require(taskId))); }

    public TaskView viewOf(TaskRecord task) {
        return new TaskView(task.id(), task.sessionId(), task.fileType(), task.status(), task.version(),
                task.errorStage(), task.errorCode(), task.errorMessage(), task.finishedAt(), task.createdAt(),
                task.updatedAt(), assets.forTask(task.userId(), task.id()), task.planId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TaskView> batchViews(String owner, List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) throw ApiException.badRequest("Provide between 1 and 100 task IDs");
        List<String> normalized = ids.stream().map(Ids::require).toList();
        return tasks.batch(Ids.require(owner), normalized).stream().map(this::viewOf).toList();
    }

    /** 接收阶段只提交短事务；返回计划表示结果 Inbox 仍未 APPLIED，调用方须继续导入。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ImportPlan admitGeneration(IncomingEvent.Generation event, Runnable markApplied) {
        IncomingEvent.Header header = event.header();
        String owner = Ids.require(header.userId());
        String session = Ids.require(header.sessionId());
        String taskId = Ids.require(header.taskId());
        if (header.sequenceNo() < 1) throw ApiException.badRequest("Connector sequence must be positive");
        chats.sessionForMutation(owner, session);
        TaskRecord task = tasks.getForMutation(owner, taskId);
        if (!task.sessionId().equals(session)) throw ApiException.conflict("Generation event Session mismatch");
        if (event.queryUnknown()) {
            if (!task.terminal() && header.sequenceNo() > task.providerEventSeq()) {
                tasks.observed(owner, taskId, task.status(), header.sequenceNo(), null);
            }
            markApplied.run();
            return null;
        }
        if (event.connectorStatus() == null) throw ApiException.badRequest("Connector status is required");
        List<ResultSource> sources = successSources(event);
        if (task.terminal()) {
            validateTerminalDuplicate(task, event, sources);
            markApplied.run();
            return null;
        }
        if (sources != null && task.payload().normalized() != null && sources.size() != 1) {
            // 新路径不能裁切供应商结果冒充成功；保留原清单供原身份重复事件核对。
            tasks.observed(owner, taskId, task.status(), header.sequenceNo(), sources);
            if (tasks.finish(owner, taskId, "FAILED", "GENERATION", "OUTPUT_FILE_COUNT_MISMATCH",
                    "Normalized generation requires exactly one output file")) notifyTerminal(task);
            markApplied.run();
            return null;
        }
        if (sources != null) {
            validateSources(sources);
            if (task.resultSources() != null && !task.resultSources().equals(sources)) {
                throw ApiException.conflict("Accepted result manifest changed");
            }
            // 同一 accepted sequence 的重投递仍需下载；不能当作旧观察直接 ACK。
            tasks.observed(owner, taskId, "IMPORTING", header.sequenceNo(), sources);
            if (!task.status().equals("IMPORTING")) sessions.publishTaskInvalidation(owner, session, taskId);
            return new ImportPlan(tasks.get(owner, taskId), sources);
        }
        if (header.sequenceNo() <= task.providerEventSeq()) { markApplied.run(); return null; }
        if (task.status().equals("IMPORTING")) throw ApiException.conflict("Connector result is already accepted");
        if (event.connectorStatus() == IncomingEvent.ConnectorExecutionStatus.FAILED) {
            IncomingEvent.GenerationError error = failure(event);
            tasks.observed(owner, taskId, task.status(), header.sequenceNo(), null);
            if (tasks.finish(owner, taskId, "FAILED", "GENERATION", error.code(), error.message())) notifyTerminal(task);
        } else {
            if (event.outcome() != null) throw ApiException.badRequest("Nonterminal outcome must be null");
            String next = event.connectorStatus() == IncomingEvent.ConnectorExecutionStatus.ACCEPTED ? "SUBMITTED" : "RUNNING";
            if (task.status().equals("RUNNING") && next.equals("SUBMITTED")) next = "RUNNING";
            tasks.observed(owner, taskId, next, header.sequenceNo(), null);
            if (!task.status().equals(next)) sessions.publishTaskInvalidation(owner, session, taskId);
        }
        markApplied.run();
        return null;
    }

    /** 全部文件先在事务外验证；终态事务再次核对完整元数据与来源清单。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeImport(ImportPlan plan, List<AssetRecord> verified) {
        TaskRecord task = importMutation(plan);
        if (task.terminal()) return;
        if (verified.size() != plan.sources().size()) throw new IllegalStateException("Incomplete result import");
        for (int index = 0; index < verified.size(); index++) {
            AssetRecord expected = verified.get(index);
            AssetRecord actual = assets.find(task.userId(), expected.id()).orElseThrow(() -> new IllegalStateException("Asset metadata missing"));
            int sourceIndex = index;
            boolean binding = assets.taskReferences(task.userId(), task.id()).stream()
                    .anyMatch(reference -> reference.assetId().equals(expected.id()) && reference.sourceIndex() == sourceIndex);
            if (!binding || !expected.id().equals(TaskResultService.sourceId(task.id(), index))
                    || !task.id().equals(actual.taskId()) || !task.userId().equals(actual.userId())
                    || actual.sizeBytes() != expected.sizeBytes() || !actual.sha256().equals(expected.sha256())
                    || !actual.filename().equals(expected.filename()) || !actual.mediaType().equals(expected.mediaType())) {
                throw new IllegalStateException("Imported asset metadata conflict");
            }
        }
        if (tasks.finish(task.userId(), task.id(), "SUCCEEDED", null, null, null)) notifyTerminal(task);
    }

    /** 字节已发布后才写元数据；重复提交核验赢家记录，不重复发布文件。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public AssetRecord registerImportAsset(ImportPlan plan, AssetRecord proposed, int sourceIndex) {
        assets.coordinateOwner(plan.task().userId());
        TaskRecord task = importMutation(plan);
        if (task.terminal()) return null;
        Optional<AssetRecord> existing = assets.find(task.userId(), proposed.id());
        if (existing.isPresent()) {
            AssetRecord actual = existing.get();
            if (!Objects.equals(actual.taskId(), task.id()) || actual.sizeBytes() != proposed.sizeBytes()
                    || !actual.sha256().equals(proposed.sha256()) || !actual.filename().equals(proposed.filename())
                    || !actual.mediaType().equals(proposed.mediaType())) throw new IllegalStateException("Asset metadata conflict");
            return actual;
        }
        assets.insert(proposed, null, sourceIndex);
        tasks.assetRegistered(task.userId(), task.id());
        sessions.publishTaskInvalidation(task.userId(), task.sessionId(), task.id());
        return proposed;
    }

    /** 确认的来源失败终结 Task；数据库或对象发布未知不能调用此方法。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void failImport(ImportPlan plan, String code, String message) {
        TaskRecord task = importMutation(plan);
        if (!task.terminal() && tasks.finish(task.userId(), task.id(), "FAILED", "IMPORT", code, message)) notifyTerminal(task);
    }

    private TaskRecord importMutation(ImportPlan plan) {
        TaskRecord original = plan.task();
        chats.sessionForMutation(original.userId(), original.sessionId());
        TaskRecord task = tasks.getForMutation(original.userId(), original.id());
        if (!task.sessionId().equals(original.sessionId()) || !Objects.equals(task.resultSources(), plan.sources())) {
            throw ApiException.conflict("Import identity or manifest changed");
        }
        if (!task.terminal() && !task.status().equals("IMPORTING")) throw new IllegalStateException("Task is not importing");
        return task;
    }

    private void notifyTerminal(TaskRecord task) {
        sessions.acceptTaskTerminal(task.userId(), task.sessionId(), task.id());
        sessions.publishTaskInvalidation(task.userId(), task.sessionId(), task.id());
    }

    private static List<ResultSource> successSources(IncomingEvent.Generation event) {
        if (event.connectorStatus() != IncomingEvent.ConnectorExecutionStatus.SUCCEEDED) return null;
        if (!(event.outcome() instanceof IncomingEvent.GenerationSuccessOutcome success) || !success.status().equals("SUCCEEDED")) {
            throw ApiException.badRequest("Success outcome is required");
        }
        return success.sources();
    }

    private static IncomingEvent.GenerationError failure(IncomingEvent.Generation event) {
        if (!(event.outcome() instanceof IncomingEvent.GenerationFailureOutcome outcome) || !outcome.status().equals("FAILED")
                || outcome.error() == null || outcome.error().code() == null || outcome.error().code().isBlank()
                || outcome.error().code().length() > 64 || outcome.error().message() == null || outcome.error().message().isBlank()) {
            throw ApiException.badRequest("Invalid generation failure");
        }
        return outcome.error();
    }

    private static void validateTerminalDuplicate(TaskRecord task, IncomingEvent.Generation event, List<ResultSource> sources) {
        if (sources != null) {
            if (!Objects.equals(task.resultSources(), sources)) throw ApiException.conflict("Terminal manifest conflict");
        } else if (event.connectorStatus() == IncomingEvent.ConnectorExecutionStatus.FAILED) {
            IncomingEvent.GenerationError error = failure(event);
            if (!task.status().equals("FAILED") || !"GENERATION".equals(task.errorStage())
                    || !Objects.equals(task.errorCode(), error.code()) || !Objects.equals(task.errorMessage(), error.message())) {
                throw ApiException.conflict("Terminal generation outcome conflict");
            }
        }
    }

    static void validateSources(List<ResultSource> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > 64) throw ApiException.badRequest("Provide between 1 and 64 sources");
        for (ResultSource source : sources) {
            java.net.URI uri = java.net.URI.create(source.url());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || source.filename().isBlank()
                    || source.filename().length() > 255 || source.filename().contains("/") || source.filename().contains("\\")
                    || source.filename().chars().anyMatch(Character::isISOControl)) throw ApiException.badRequest("Invalid result source");
            if (source.mediaType() == null || source.mediaType().isBlank() || source.mediaType().length() > 128) {
                throw ApiException.badRequest("Invalid result media type");
            }
            MediaType type = MediaType.parseMediaType(source.mediaType());
            if (type.isWildcardType() || type.isWildcardSubtype()) throw ApiException.badRequest("Invalid result media type");
        }
    }

    /** 仅修复生成前段观察，不下载、不重提任务、不改变原操作身份。 */
    public synchronized void reconcileStale() {
        List<TaskRecord> candidates = tasks.staleTasks(observationCursor);
        if (candidates.isEmpty() && observationCursor != null) {
            observationCursor = null;
            candidates = tasks.staleTasks(null);
        }
        for (TaskRecord candidate : candidates) {
            transactions.executeWithoutResult(status -> {
                chats.sessionForMutation(candidate.userId(), candidate.sessionId());
                TaskRecord current = tasks.getForMutation(candidate.userId(), candidate.id());
                if (!List.of("CREATED", "SUBMITTED", "RUNNING").contains(current.status())) return;
                CommandPayload.TaskStatusQuery query = new CommandPayload.TaskStatusQuery(2, Ids.next(), "TASK_STATUS_QUERY",
                        Timestamps.now().toString(), current.userId(), current.sessionId(), current.id(), current.providerEventSeq());
                outbox.enqueueEnvelope(Topics.GENERATION_COMMANDS, current.userId() + ":" + current.id(), query);
            });
            observationCursor = new TaskRepository.ObservationCursor(candidate.updatedAt(), candidate.id());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateSessionTaskRequest(String operationId, String sessionId, String kind, ConnectorRequest request) {}
    /** 原 Task 与固定来源清单是恢复计划，不是另一张导入队列表。 */
    public record ImportPlan(TaskRecord task, List<ResultSource> sources) {
        public ImportPlan { sources = List.copyOf(sources); }
    }
}
