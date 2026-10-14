package com.lux.backend.chat;

import com.lux.backend.task.ConnectorRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;
import static com.lux.backend.generation.GenerationPlanContract.*;
import java.util.List;

/** 会话内容的具名变体；已知业务字段不由匿名 JSON 字典传递。 */
public final class ConversationContent {
    private ConversationContent() {
    }

    /** Item 的完整内容；来源与 kind 由外层快照声明。 */
    public sealed interface Payload permits OrderedContent, ToolCallContent, ToolResultContent, TaskEventContent {
    }

    /** 数组顺序就是展示顺序，临时流式数组保留每个原始片段。 */
    public record OrderedContent(List<ContentBlock> blocks) implements Payload {
        public OrderedContent {
            blocks = List.copyOf(blocks);
        }
    }

    /** 模型真正调用的工具参数，工具名在 Item 外层。 */
    public sealed interface ToolCallContent extends Payload permits SubmitGenerationCallContent, GetTaskCallContent, PlanCallContent {
    }

    public record SubmitGenerationCallContent(SubmitGenerationArguments arguments) implements ToolCallContent {
    }

    public record GetTaskCallContent(GetTaskArguments arguments) implements ToolCallContent {
    }

    /** 计划工具参数保持合同具名类型；调用身份由外层 Item 与 Session 提供。 */
    public sealed interface PlanCallContent extends ToolCallContent permits GetModelCatalogCallContent, ReadGenerationPlanCallContent, CreateGenerationPlanCallContent, UpdateGenerationPlanCallContent, ChangeGenerationTargetCallContent, CloneGenerationPlanCallContent, FocusGenerationPlanCallContent, SubmitGenerationPlanCallContent {
        JavaPlanToolInvocation invocation(UUID operationId, UUID sessionId);
    }

    /** get_model_catalog 的模型参数与原操作身份绑定。 */
    public record GetModelCatalogCallContent(GetModelCatalogArguments arguments) implements PlanCallContent {
        @Override public JavaPlanToolInvocation invocation(UUID operationId, UUID sessionId) {
            return new GetModelCatalogInvocation("generation_plan_tool_invocation", operationId, sessionId,
                "get_model_catalog", arguments);
        }
    }

    /** read_generation_plan 的模型参数与原操作身份绑定。 */
    public record ReadGenerationPlanCallContent(ReadGenerationPlanArguments arguments) implements PlanCallContent {
        @Override public JavaPlanToolInvocation invocation(UUID operationId, UUID sessionId) {
            return new ReadGenerationPlanInvocation("generation_plan_tool_invocation", operationId, sessionId,
                "read_generation_plan", arguments);
        }
    }

    /** create_generation_plan 的模型参数与原操作身份绑定。 */
    public record CreateGenerationPlanCallContent(CreateGenerationPlanArguments arguments) implements PlanCallContent {
        @Override public JavaPlanToolInvocation invocation(UUID operationId, UUID sessionId) {
            return new CreateGenerationPlanInvocation("generation_plan_tool_invocation", operationId, sessionId,
                "create_generation_plan", arguments);
        }
    }

    /** update_generation_plan 的模型参数与原操作身份绑定。 */
    public record UpdateGenerationPlanCallContent(UpdateGenerationPlanArguments arguments) implements PlanCallContent {
        @Override public JavaPlanToolInvocation invocation(UUID operationId, UUID sessionId) {
            return new UpdateGenerationPlanInvocation("generation_plan_tool_invocation", operationId, sessionId,
                "update_generation_plan", arguments);
        }
    }

    /** change_generation_target 的模型参数与原操作身份绑定。 */
    public record ChangeGenerationTargetCallContent(ChangeGenerationTargetArguments arguments) implements PlanCallContent {
        @Override public JavaPlanToolInvocation invocation(UUID operationId, UUID sessionId) {
            return new ChangeGenerationTargetInvocation("generation_plan_tool_invocation", operationId, sessionId,
                "change_generation_target", arguments);
        }
    }

    /** clone_generation_plan 的模型参数与原操作身份绑定。 */
    public record CloneGenerationPlanCallContent(CloneGenerationPlanArguments arguments) implements PlanCallContent {
        @Override public JavaPlanToolInvocation invocation(UUID operationId, UUID sessionId) {
            return new CloneGenerationPlanInvocation("generation_plan_tool_invocation", operationId, sessionId,
                "clone_generation_plan", arguments);
        }
    }

    /** focus_generation_plan 的模型参数与原操作身份绑定。 */
    public record FocusGenerationPlanCallContent(FocusGenerationPlanArguments arguments) implements PlanCallContent {
        @Override public JavaPlanToolInvocation invocation(UUID operationId, UUID sessionId) {
            return new FocusGenerationPlanInvocation("generation_plan_tool_invocation", operationId, sessionId,
                "focus_generation_plan", arguments);
        }
    }

    /** submit_generation_plan 的模型参数与原操作身份绑定。 */
    public record SubmitGenerationPlanCallContent(SubmitGenerationPlanArguments arguments) implements PlanCallContent {
        @Override public JavaPlanToolInvocation invocation(UUID operationId, UUID sessionId) {
            return new SubmitGenerationPlanInvocation("generation_plan_tool_invocation", operationId, sessionId,
                "submit_generation_plan", arguments);
        }
    }

    public record SubmitGenerationArguments(String kind, ConnectorRequest request) {
    }

    public record GetTaskArguments(String taskId) {
    }

    /** 每个调用的不可变结果，type 在 JSON 中明确区分观察与受理。 */
    public sealed interface ToolResultContent extends Payload permits TaskSubmissionReceipt, TaskQuerySnapshot,
        ToolError, PlanResultContent {
    }

    /** 不增加 wire 包装层，保存完整具名计划工具结果。 */
    public record PlanResultContent(@JsonValue GenerationPlanToolResult result) implements ToolResultContent {
    }

    public record TaskSubmissionReceipt(String taskId, boolean accepted) implements ToolResultContent {
        @JsonProperty("type")
        public String type() {
            return "task_submission_receipt";
        }
    }

    public record TaskQuerySnapshot(String taskId, long version, String status, List<TaskAssetReference> assets,
        TaskFailure failure) implements ToolResultContent {
        public TaskQuerySnapshot {
            assets = List.copyOf(assets);
        }
        @JsonProperty("type")
        public String type() {
            return "task_query_snapshot";
        }
    }

    public record TaskAssetReference(String assetId, int sourceIndex) {
    }

    public record TaskFailure(String stage, String code, String message) {
    }

    public record ToolError(String code, String message) implements ToolResultContent {
        @JsonProperty("type")
        public String type() {
            return "tool_error";
        }
    }

    /** 背景任务终态只通知业务身份，真正结果由下一次 get_task 查询。 */
    public record TaskEventContent(String taskId) implements Payload {
    }
}
