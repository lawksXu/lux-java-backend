package com.lux.backend.chat;

import com.lux.backend.asset.AssetRepository;
import com.lux.backend.common.ApiException;
import com.lux.backend.common.Ids;
import com.lux.backend.common.HttpIdempotency;
import com.lux.backend.generation.GenerationPlanCodec;
import com.lux.backend.generation.GenerationPlanOperationIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import static com.lux.backend.generation.GenerationPlanContract.*;
import com.lux.backend.task.TaskRecord;
import com.lux.backend.task.TaskRepository;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.lux.backend.chat.ConversationContent.*;

/** 输入和输出共用资源归属校验；空白流片段可合法保留，用户输入仍要求有实际内容。 */
@Component
public class ContentValidator {
    /** 资源存在性与所有者校验，不向模型暴露未验证来源 URL。 */
    private final AssetRepository assets;
    /** 只读取 Task 的固定 owner/Session 关联，历史工具结果不与最新状态比较。 */
    private final TaskRepository tasks;

    /** 原操作回执是历史观察的唯一权威，不读取最新计划或焦点。 */
    private final HttpIdempotency operations;
    private final GenerationPlanCodec planCodec;

    public ContentValidator(AssetRepository assets, TaskRepository tasks) {
        this(assets, tasks, null, new GenerationPlanCodec());
    }

    @Autowired
    public ContentValidator(AssetRepository assets, TaskRepository tasks, HttpIdempotency operations,
                            GenerationPlanCodec planCodec) {
        this.assets = assets;
        this.tasks = tasks;
        this.operations = operations;
        this.planCodec = planCodec;
    }

    /** 用户输入要求实际内容，资源引用必须属于受理用户。 */
    public void validate(String user, List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty() || blocks.size() > 64) {
            throw ApiException.badRequest("Content must contain between 1 and 64 blocks");
        }
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.Text text
                && (text.text() == null || text.text().isBlank() || text.text().length() > 64000)) {
                throw ApiException.badRequest("Invalid user text");
            }
        }
        validateOutput(user, blocks);
    }

    /** 输出允许空白文本，仍校验资源归属及文本大小。 */
    public void validateOutput(String user, List<ContentBlock> blocks) {
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.Asset asset) {
                assets.get(user, Ids.require(asset.assetId()));
            }
            else if (block instanceof ContentBlock.Text text
                && (text.text() == null || text.text().length() > 1_000_000)) {
                throw new IllegalArgumentException("Invalid output text");
            }
        }
    }

    /**
     * 来源事实先校验固定业务引用，再进入 journal/投影事务。
     * 工具结果可先于调用事实到达；Task 和 Asset 已由同步业务接口持久化。
     */
    public void validateSourcePayload(String user, String session, Payload payload) {
        if (payload instanceof OrderedContent ordered) {
            validateOutput(user, ordered.blocks());
        }
        else if (payload instanceof SubmitGenerationCallContent submission) {
            for (String assetId : submission.arguments().request().assetIds()) {
                assets.get(user, Ids.require(assetId));
            }
        }
        else if (payload instanceof PlanResultContent planResult) {
            validatePlanResult(user, session, planResult.result());
        }
        else if (payload instanceof TaskSubmissionReceipt receipt) {
            requireTaskSession(user, session, receipt.taskId());
        }
        else if (payload instanceof TaskQuerySnapshot snapshot) {
            requireTaskSession(user, session, snapshot.taskId());
            List<TaskAssetReference> stored = assets.taskReferences(user, snapshot.taskId());
            for (TaskAssetReference reference : snapshot.assets()) {
                if (!stored.contains(reference)) {
                    throw new IllegalArgumentException("Task result Asset does not match its original Task/source index");
                }
            }
            // 新状态、新版本及后来导入的资源不会改写已经确认的历史查询结果。
        }
    }

    /** 已知调用配对后校验原操作身份；结果先到时仅暂存，不能创造调用事实或外键。 */
    public void validateToolPair(String user, String session, String callItemId, ToolCallContent call, ToolResultContent result) {
        if (call instanceof PlanCallContent planCall) {
            if (!(result instanceof PlanResultContent planResult) || operations == null) {
                throw new IllegalArgumentException("Plan tool result requires its original operation receipt");
            }
            JavaPlanToolInvocation invocation = planCall.invocation(UUID.fromString(Ids.require(callItemId)),
                UUID.fromString(Ids.require(session)));
            String original = operations.originalResponse(user, callItemId).orElseThrow(() ->
                new IllegalArgumentException("Original plan tool operation receipt is missing"));
            JavaPlanToolOperationReceipt receipt = planCodec.read(original, JavaPlanToolOperationReceipt.class);
            String hash = operations.requestHash("generation-plan-tools-v1",
                new GenerationPlanOperationIdentity(UUID.fromString(Ids.require(user)), invocation));
            if (!receipt.operationId().equals(invocation.operationId())
                || !receipt.sessionId().equals(invocation.sessionId())
                || !receipt.toolName().name().equals(invocation.toolName())
                || !receipt.requestHash().equals(hash)
                || !receipt.result().equals(planResult.result())) {
                throw new IllegalArgumentException("Plan tool result does not match its original operation receipt");
            }
            validatePlanResult(user, session, planResult.result());
        }
        else if (result instanceof PlanResultContent) {
            throw new IllegalArgumentException("Plan tool result requires a plan tool call");
        }
        else if (call instanceof SubmitGenerationCallContent && result instanceof TaskSubmissionReceipt receipt) {
            TaskRecord task = requireTaskSession(user, session, receipt.taskId());
            if (!callItemId.equals(task.operationId())) {
                throw new IllegalArgumentException("Submission Task does not belong to the original call operation");
            }
        }
    }

    /** 来源结果先验证固定归属；配对后再验证完整原回执，允许结果先于调用到达。 */
    private void validatePlanResult(String user, String session, GenerationPlanToolResult result) {
        if (result instanceof GenerationPlanReadResult read) {
            requireFocusSession(session, read.focus());
            for (GenerationPlanSnapshot snapshot : read.plans()) requirePlanScope(user, session, snapshot.plan());
        }
        else if (result instanceof PlanMutationReceipt mutation) {
            requireFocusSession(session, mutation.focus());
            requirePlanScope(user, session, mutation.snapshot().plan());
        }
        else if (result instanceof PlanFocusReceipt focus) {
            requireFocusSession(session, focus.focus());
        }
        else if (result instanceof PlanTaskSubmissionReceipt submission) {
            requireFocusSession(session, submission.focus());
            if (submission.tasks().size() != submission.requestedFileCount()) {
                throw new IllegalArgumentException("Plan submission child count does not match receipt");
            }
            Set<UUID> taskIds = new HashSet<>();
            Set<UUID> operationIds = new HashSet<>();
            for (int index = 0; index < submission.tasks().size(); index++) {
                SubmittedTask child = submission.tasks().get(index);
                if (child.index() != index || !taskIds.add(child.taskId()) || !operationIds.add(child.taskOperationId())) {
                    throw new IllegalArgumentException("Plan submission children require ordered unique identities");
                }
                TaskRecord task = requireTaskSession(user, session, child.taskId().toString());
                if (!submission.planId().toString().equals(task.planId())
                    || !child.taskOperationId().toString().equals(task.operationId())) {
                    throw new IllegalArgumentException("Plan submission child does not belong to its original plan/operation");
                }
            }
        }
    }

    private void requireFocusSession(String session, SessionPlanFocus focus) {
        if (!session.equals(focus.sessionId().toString())) {
            throw new IllegalArgumentException("Plan focus result belongs to another Session");
        }
    }

    private void requirePlanScope(String user, String session, GenerationPlan plan) {
        if (!user.equals(plan.userId().toString()) || !session.equals(plan.sessionId().toString())) {
            throw new IllegalArgumentException("Plan result belongs to another owner/Session");
        }
    }

    private TaskRecord requireTaskSession(String user, String session, String taskId) {
        TaskRecord task = tasks.get(user, Ids.require(taskId));
        if (!session.equals(task.sessionId())) {
            throw new IllegalArgumentException("Task result belongs to another Session");
        }
        return task;
    }
}
