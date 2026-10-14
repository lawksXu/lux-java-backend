package com.lux.backend.infrastructure;

import com.lux.backend.chat.ChatModels;
import com.lux.backend.chat.ContentBlock;
import com.lux.backend.chat.ConversationContent;
import com.lux.backend.common.Ids;
import com.lux.backend.task.ConnectorRequest;
import com.lux.backend.generation.GenerationPlanCodec;
import static com.lux.backend.generation.GenerationPlanContract.*;
import com.lux.backend.task.ResultSource;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static com.lux.backend.infrastructure.IncomingEvent.*;
import static com.lux.backend.chat.ConversationContent.*;

/** 协议入口：严格读取已知字段，在归档原始信封的同时生成可验证的具名业务类型。 */
public final class EventProtocol {
    /** 严格合同解码器只在 JSON 协议入口使用。 */
    private static final GenerationPlanCodec PLAN_CODEC = new GenerationPlanCodec();

    private EventProtocol() {
    }

    public static void validate(String source, JsonNode value) {
        if (source.equals(Topics.AGENT_EVENTS)) {
            agent(value);
        } else if (source.equals(Topics.GENERATION_EVENTS) || source.equals(Topics.LEGACY_GENERATION_EVENTS)) {
            generation(value);
        } else {
            throw invalid("Unexpected event source");
        }
    }

    /** 执行事实按 Session 排序；真正 Run/Turn/Item 身份位于具名 body 内。 */
    public static ExecutionFact agent(JsonNode value) {
        only(value, "schema_version", "event_id", "event_type", "occurred_at", "user_id", "session_id", "event_seq", "body");
        requireVersion(value, 2);
        String type = string(value, "event_type");
        ExecutionHeader header = new ExecutionHeader(2, uuid(value, "event_id"), type, sourceTimestampString(value,
            "occurred_at"), uuid(value, "user_id"), uuid(value, "session_id"), positive(value, "event_seq"));
        JsonNode body = object(value, "body");
        ExecutionBody decoded = switch (type) {
            case "RUN_SNAPSHOT" -> runSnapshot(body);
            case "TURN_SNAPSHOT" -> turnSnapshot(body);
            case "AGENT_ITEM_SNAPSHOT" -> itemSnapshot(body);
            case "MODEL_RESPONSE_COMPLETED" -> response(body);
            case "ITEM_TEXT_DELTA" -> delta(body);
            default -> throw new IllegalArgumentException("Unsupported execution fact type");
        };
        return new ExecutionFact(header, decoded, value.deepCopy());
    }

    public static SessionExecutionSync sync(JsonNode value) {
        only(value, "user_id", "session_id", "event_high_watermark", "events", "runs", "turns", "items", "next_after_event_seq");
        String user = uuid(value, "user_id"), session = uuid(value, "session_id");
        long high = nonnegative(value, "event_high_watermark"), previous = 0;
        List<ExecutionFact> events = new ArrayList<>();
        for (JsonNode raw : array(value, "events")) {
            ExecutionFact fact = agent(raw);
            if (!fact.header().userId().equals(user)
                || !fact.header().sessionId().equals(session)
                || fact.header().eventSeq() <= previous
                || fact.header().eventSeq() > high) {
                throw invalid("Sync event range/routing");
            }
            previous = fact.header().eventSeq();
            events.add(fact);
        }
        List<RunSnapshot> runs = new ArrayList<>();
        for (JsonNode raw : array(value, "runs")) {
            runs.add(runSnapshot(raw));
        }
        List<TurnSnapshot> turns = new ArrayList<>();
        for (JsonNode raw : array(value, "turns")) {
            turns.add(turnSnapshot(raw));
        }
        List<AgentItemSnapshot> items = new ArrayList<>();
        for (JsonNode raw : array(value, "items")) {
            items.add(itemSnapshot(raw));
        }
        Long next = optionalNumber(value, "next_after_event_seq");
        if (next != null && (next > high || events.isEmpty() || next != previous)) {
            throw invalid("Sync pagination cursor");
        }
        return new SessionExecutionSync(user, session, high, List.copyOf(events), List.copyOf(runs), List.copyOf(turns),
            List.copyOf(items), next);
    }

    public static CommandPayload.InputAccepted input(JsonNode value) {
        only(value, "schema_version", "event_id", "event_type", "occurred_at", "user_id", "session_id", "item");
        requireVersion(value, 2);
        if (!"INPUT_ACCEPTED".equals(string(value, "event_type"))) {
            throw invalid("Input event type");
        }
        ChatModels.AcceptedInputItem item = acceptedItem(object(value, "item"));
        String eventId = uuid(value, "event_id");
        if (!eventId.equals(item.id())) {
            throw invalid("Input event/Item identity");
        }
        return new CommandPayload.InputAccepted(2, eventId, "INPUT_ACCEPTED", timestampString(value, "occurred_at"),
            uuid(value, "user_id"), uuid(value, "session_id"), item);
    }

    public static ChatModels.AcceptedInputItem acceptedItem(JsonNode value) {
        only(value, "id", "sequence_no", "input_sequence_no", "source", "kind", "content");
        String source = oneOf(string(value, "source"), "USER", "SYSTEM");
        String kind = oneOf(string(value, "kind"), "MESSAGE", "TASK_EVENT");
        if (kind.equals("TASK_EVENT") && !source.equals("SYSTEM")) {
            throw invalid("Task input provenance");
        }
        if (value.has("run_id") || value.has("turn_id") || value.has("role")) {
            throw invalid("Input execution/role field");
        }
        return new ChatModels.AcceptedInputItem(uuid(value, "id"), positive(value, "sequence_no"), positive(value,
            "input_sequence_no"), source, kind, payload(kind, null, object(value, "content")));
    }

    public static RunSnapshot runSnapshot(JsonNode value) {
        only(value, "run", "source_revision", "status", "started_at", "finished_at", "final_item", "error_code", "error_message");
        RunIdentity identity = runIdentity(object(value, "run"));
        String status = oneOf(string(value, "status"), "RUNNING", "COMPLETED", "FAILED", "CANCELLED");
        Instant finished = optionalTime(value, "finished_at");
        terminalTime(status, finished);
        AgentItemIdentity finalItem = value.hasNonNull("final_item") ? itemIdentity(object(value, "final_item")) : null;
        if (status.equals("COMPLETED") && finalItem == null) {
            throw invalid("Completed Run final Item");
        }
        if (finalItem != null && !identity.equals(finalItem.turn().run())) {
            throw invalid("Run final Item routing");
        }
        return new RunSnapshot(identity, positive(value, "source_revision"), status, optionalTime(value, "started_at"),
            finished, finalItem, boundedOptional(value, "error_code", 64), optionalTextColumn(value, "error_message"));
    }

    public static TurnSnapshot turnSnapshot(JsonNode value) {
        only(value, "turn", "source_revision", "status", "started_at", "finished_at", "error_code", "error_message");
        String status = oneOf(string(value, "status"), "PREPARED", "RUNNING", "INTERRUPTED", "COMPLETED",
            "FAILED", "CANCELLED");
        Instant started = optionalTime(value, "started_at");
        Instant finished = optionalTime(value, "finished_at");
        terminalTime(status, finished);
        if (status.equals("CANCELLED") && started != null) {
            throw invalid("Started Turn cannot be cancelled");
        }
        return new TurnSnapshot(turnIdentity(object(value, "turn")), positive(value, "source_revision"), status,
            started, finished, boundedOptional(value, "error_code", 64), optionalTextColumn(value, "error_message"));
    }

    public static AgentItemSnapshot itemSnapshot(JsonNode value) {
        only(value, "item", "source", "source_revision", "kind", "status", "payload", "completed_at", "call_id", "tool_name");
        AgentItemIdentity identity = itemIdentity(object(value, "item"));
        if (!string(value, "source").equals("AGENT") || value.has("role")) {
            throw invalid("Agent provenance");
        }
        long revision = positive(value, "source_revision");
        String kind = oneOf(string(value, "kind"), "MESSAGE", "REASONING_SUMMARY", "TOOL_CALL", "TOOL_RESULT");
        String status = oneOf(string(value, "status"), "PARTIAL", "COMPLETED", "INTERRUPTED");
        String call = optional(value, "call_id");
        String tool = optional(value, "tool_name");
        boolean called = kind.equals("TOOL_CALL") || kind.equals("TOOL_RESULT");
        if (called != (call != null && !call.isBlank()) || (call != null && call.length() > 255)) {
            throw invalid("Call identity");
        }
        if (kind.equals("TOOL_CALL") != (tool != null) || (tool != null && tool.length() > 255)) {
            throw invalid("Tool name");
        }
        if (called && !status.equals("COMPLETED")) {
            throw invalid("Incomplete call content");
        }
        Instant completed = optionalTime(value, "completed_at");
        if (status.equals("COMPLETED") != (completed != null)) {
            throw invalid("Item completion time");
        }
        Payload content = payload(kind, tool, object(value, "payload"));
        if (status.equals("PARTIAL")
            && (revision != 1
            || !(content instanceof OrderedContent ordered)
            || !ordered.blocks().isEmpty())) {
            throw invalid("Initial PARTIAL snapshot must have revision one and empty content");
        }
        return new AgentItemSnapshot(identity, "AGENT", revision, kind, status, content, completed, call,
            tool);
    }

    public static ItemTextDelta delta(JsonNode value) {
        only(value, "item", "stream_seq", "text");
        return new ItemTextDelta(itemIdentity(object(value, "item")), positive(value, "stream_seq"), string(value,
            "text"));
    }

    private static ModelResponseCompleted response(JsonNode value) {
        only(value, "model_execution_id", "turn", "items");
        TurnIdentity turn = turnIdentity(object(value, "turn"));
        List<AgentItemSnapshot> items = new ArrayList<>();
        JsonNode array = array(value, "items");
        long previous = 0;
        for (JsonNode item : array) {
            AgentItemSnapshot snapshot = itemSnapshot(item);
            if (!snapshot.item().turn().equals(turn)
                || !snapshot.status().equals("COMPLETED")
                || snapshot.kind().equals("TOOL_RESULT")
                || snapshot.item().itemIndex() <= previous) {
                throw invalid("Invalid complete response member");
            }
            if (items.stream().anyMatch(existing -> existing.item().id().equals(snapshot.item().id()))) {
                throw invalid("Duplicate response Item");
            }
            if (snapshot.callId() != null && items.stream().anyMatch(existing -> snapshot.callId().equals(existing.callId()))) {
                throw invalid("Duplicate response call ID");
            }
            previous = snapshot.item().itemIndex();
            items.add(snapshot);
        }
        String modelId = string(value, "model_execution_id");
        if (modelId.isBlank() || modelId.length() > 255) {
            throw invalid("Model execution identity");
        }
        return new ModelResponseCompleted(modelId, turn, items);
    }

    public static RunIdentity runIdentity(JsonNode value) {
        only(value, "id", "run_index");
        return new RunIdentity(uuid(value, "id"), positive(value, "run_index"));
    }

    public static TurnIdentity turnIdentity(JsonNode value) {
        only(value, "id", "turn_index", "run");
        return new TurnIdentity(uuid(value, "id"), positive(value, "turn_index"), runIdentity(object(value,
            "run")));
    }

    public static AgentItemIdentity itemIdentity(JsonNode value) {
        only(value, "id", "item_index", "turn");
        return new AgentItemIdentity(uuid(value, "id"), positive(value, "item_index"), turnIdentity(object(value,
            "turn")));
    }

    /** DB JSON 的恢复也依赖已知 kind/tool_name，不依赖字符串键的业务散读。 */
    public static Payload payload(String kind, String tool, JsonNode value) {
        return switch (kind) {
            case "MESSAGE", "REASONING_SUMMARY" -> {
                only(value, "blocks");
                List<ContentBlock> blocks = new ArrayList<>();
                for (JsonNode block : array(value, "blocks")) {
                    blocks.add(ContentBlock.decode(block));
                }
                yield new OrderedContent(blocks);
            }
            case "TASK_EVENT" -> {
                only(value, "task_id");
                if (value.size() != 1) {
                    throw invalid("Task event contains only task_id");
                }
                yield new TaskEventContent(uuid(value, "task_id"));
            }
            case "TOOL_CALL" -> {
                only(value, "arguments");
                JsonNode args = object(value, "arguments");
                yield switch (tool) {
                    case "submit_generation" -> {
                        only(args, "kind", "request");
                        yield new SubmitGenerationCallContent(new SubmitGenerationArguments(oneOf(string(args,
                            "kind"), "IMAGE", "VIDEO"), ConnectorRequest.decode(object(args, "request"))));
                    }
                    case "get_task" -> {
                        only(args, "task_id");
                        yield new GetTaskCallContent(new GetTaskArguments(uuid(args, "task_id")));
                    }
                    case "get_model_catalog" -> new GetModelCatalogCallContent(PLAN_CODEC.read(args, GetModelCatalogArguments.class));
                    case "read_generation_plan" -> new ReadGenerationPlanCallContent(PLAN_CODEC.read(args, ReadGenerationPlanArguments.class));
                    case "create_generation_plan" -> new CreateGenerationPlanCallContent(PLAN_CODEC.read(args, CreateGenerationPlanArguments.class));
                    case "update_generation_plan" -> new UpdateGenerationPlanCallContent(PLAN_CODEC.read(args, UpdateGenerationPlanArguments.class));
                    case "change_generation_target" -> new ChangeGenerationTargetCallContent(PLAN_CODEC.read(args, ChangeGenerationTargetArguments.class));
                    case "clone_generation_plan" -> new CloneGenerationPlanCallContent(PLAN_CODEC.read(args, CloneGenerationPlanArguments.class));
                    case "focus_generation_plan" -> new FocusGenerationPlanCallContent(PLAN_CODEC.read(args, FocusGenerationPlanArguments.class));
                    case "submit_generation_plan" -> new SubmitGenerationPlanCallContent(PLAN_CODEC.read(args, SubmitGenerationPlanArguments.class));
                    default -> throw invalid("Unknown tool");
                };
            }
            case "TOOL_RESULT" -> {
                yield switch (string(value, "type")) {
                    case "task_submission_receipt" -> {
                        only(value, "type", "task_id", "accepted");
                        if (!value.path("accepted").isBoolean() || !value.path("accepted").asBoolean()) {
                            throw invalid("Submission acceptance");
                        }
                        yield new TaskSubmissionReceipt(uuid(value, "task_id"), true);
                    }
                    case "tool_error" -> {
                        only(value, "type", "code", "message");
                        yield new ToolError(nonempty(value, "code"), string(value, "message"));
                    }
                    case "task_query_snapshot" -> taskQuery(value);
                    case "model_catalog_snapshot", "generation_plan_read_result", "generation_plan_mutation_receipt",
                        "generation_plan_focus_receipt", "plan_task_submission_receipt", "generation_plan_tool_rejection" ->
                        new PlanResultContent(PLAN_CODEC.decodeToolResult(value));
                    default -> throw invalid("Unknown tool outcome");
                };
            }
            default -> throw invalid("Unknown Item kind");
        };
    }

    private static TaskQuerySnapshot taskQuery(JsonNode value) {
        only(value, "type", "task_id", "version", "status", "assets", "failure");
        String status = oneOf(string(value, "status"), "CREATED", "SUBMITTED", "RUNNING", "IMPORTING", "SUCCEEDED",
            "FAILED");
        List<TaskAssetReference> assets = new ArrayList<>();
        long previous = -1;
        for (JsonNode asset : array(value, "assets")) {
            only(asset, "asset_id", "source_index");
            long index = nonnegative(asset, "source_index");
            if (index > Integer.MAX_VALUE) {
                throw invalid("Asset index overflow");
            }
            String assetId = uuid(asset, "asset_id");
            if (index <= previous || assets.stream().anyMatch(reference -> reference.assetId().equals(assetId))) {
                throw invalid("Task Assets require unique identities and increasing source indexes");
            }
            assets.add(new TaskAssetReference(assetId, (int) index));
            previous = index;
        }
        TaskFailure failure = null;
        if (value.hasNonNull("failure")) {
            JsonNode detail = object(value, "failure");
            only(detail, "stage", "code", "message");
            failure = new TaskFailure(oneOf(string(detail, "stage"), "GENERATION", "IMPORT"), boundedOptional(detail,
                "code", 64), optionalTextColumn(detail, "message"));
        }
        if (status.equals("FAILED") != (failure != null)) {
            throw invalid("Task failure detail");
        }
        return new TaskQuerySnapshot(uuid(value, "task_id"), positive(value, "version"), status, assets, failure);
    }

    public static ChatModels.ItemProjection itemProjection(JsonNode value) {
        String kind = optional(value, "kind");
        String tool = optional(value, "tool_name");
        return new ChatModels.ItemProjection(uuid(value, "id"), uuid(value, "user_id"), uuid(value, "session_id"),
            positive(value, "sequence_no"), string(value, "source"), kind, optionalUuid(value, "run_id"),
            optionalUuid(value, "turn_id"), optionalNumber(value, "item_index"), optionalNumber(value, "input_sequence_no"),
            optional(value, "status"), optional(value, "call_id"), tool, optionalUuid(value, "task_id"), value.hasNonNull("payload") ? payload(kind,
            tool, object(value, "payload")) : null, nonnegative(value, "source_revision"), optionalTime(value,
            "completed_at"), positive(value, "version"), Instant.parse(string(value, "created_at")), Instant.parse(string(value,
            "updated_at")));
    }

    /** 仅边界兼容旧生成观察；旧 provider ack 从不改称新协议 ACCEPTED。 */
    public static Generation generation(JsonNode value) {
        long declaredVersion = nonnegative(value, "schema_version");
        if (declaredVersion != 1 && declaredVersion != 2) {
            throw invalid("Generation protocol version");
        }
        int version = (int) declaredVersion;
        String type = value.hasNonNull("event_type") ? string(value, "event_type") : "GENERATION_EVENT";
        Header header = new Header(version, uuid(value, "event_id"), type, uuid(value, "user_id"), uuid(value,
            "session_id"), uuid(value, "task_id"), positive(value, "sequence_no"), Instant.parse(timestampString(value,
            "occurred_at")));
        if (version == 2) {
            ConnectorExecutionStatus status = ConnectorExecutionStatus.valueOf(string(value, "connector_status"));
            GenerationOutcome outcome = null;
            if (status == ConnectorExecutionStatus.SUCCEEDED) {
                JsonNode body = object(value, "outcome");
                if (!string(body, "status").equals("SUCCEEDED")) {
                    throw invalid("Generation outcome status");
                }
                outcome = new GenerationSuccessOutcome("SUCCEEDED", sources(array(body, "sources")));
            } else if (status == ConnectorExecutionStatus.FAILED) {
                JsonNode body = object(value, "outcome");
                if (!string(body, "status").equals("FAILED")) {
                    throw invalid("Generation outcome status");
                }
                outcome = new GenerationFailureOutcome("FAILED", generationError(object(body, "error")));
            } else if (value.hasNonNull("outcome")) {
                throw invalid("Progress must have null outcome");
            }
            return new Generation(header, status, outcome, false);
        }
        JsonNode body = object(value, "payload");
        String status = switch (type) {
            case "TASK_SUBMITTED", "TASK_RUNNING" -> "RUNNING";
            case "TASK_RESULT" -> "SUCCEEDED";
            case "TASK_FAILED" -> "FAILED";
            case "TASK_STATUS" -> string(body, "status");
            default -> throw invalid("Legacy generation type");
        };
        if (status.equals("UNKNOWN")) {
            return new Generation(header, null, null, true);
        }
        if (status.equals("SUBMITTED")) {
            status = "RUNNING";
        }
        ConnectorExecutionStatus parsed = ConnectorExecutionStatus.valueOf(status);
        GenerationOutcome outcome = parsed == ConnectorExecutionStatus.SUCCEEDED ? new GenerationSuccessOutcome("SUCCEEDED",
            sources(array(body, "sources"))) : parsed == ConnectorExecutionStatus.FAILED ? new GenerationFailureOutcome("FAILED",
            new GenerationError("LEGACY_GENERATION_FAILURE", "PROVIDER", body.hasNonNull("error_message") ? textColumn(string(body,
            "error_message")) : "Generation failed", null)) : null;
        return new Generation(header, parsed, outcome, false);
    }

    private static GenerationError generationError(JsonNode value) {
        only(value, "code", "stage", "message", "provider_diagnostic");
        String code = nonempty(value, "code");
        if (code.length() > 64) {
            throw invalid("Generation error code length");
        }
        ProviderErrorDiagnostic diagnostic = null;
        if (value.hasNonNull("provider_diagnostic")) {
            JsonNode detail = object(value, "provider_diagnostic");
            only(detail, "provider_id", "provider_code", "provider_message");
            diagnostic = new ProviderErrorDiagnostic(nonempty(detail, "provider_id"), optional(detail, "provider_code"),
                optional(detail, "provider_message"));
        }
        return new GenerationError(code, oneOf(string(value, "stage"), "VALIDATION", "PROVIDER", "RESULT_PRESERVATION"),
            textColumn(string(value, "message")), diagnostic);
    }

    private static List<ResultSource> sources(JsonNode array) {
        if (array.isEmpty() || array.size() > 64) {
            throw invalid("Result source count");
        }
        List<ResultSource> result = new ArrayList<>();
        for (JsonNode value : array) {
            string(value, "filename");
            result.add(ResultSource.decode(value));
        }
        return List.copyOf(result);
    }

    private static void terminalTime(String status, Instant time) {
        if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(status) != (time != null)) {
            throw invalid("Terminal time");
        }
    }

    private static void requireVersion(JsonNode value, long expected) {
        if (value == null || !value.isObject() || nonnegative(value, "schema_version") != expected) {
            throw invalid("Unsupported event envelope");
        }
    }

    public static String string(JsonNode value, String field) {
        if (!value.path(field).isString()) {
            throw invalid("Invalid string field: " + field);
        }
        return value.path(field).asString();
    }

    private static String nonempty(JsonNode value, String field) {
        String result = string(value, field);
        if (result.isBlank()) {
            throw invalid("Empty " + field);
        }
        return result;
    }

    public static String optional(JsonNode value, String field) {
        return value.hasNonNull(field) ? string(value, field) : null;
    }

    /** TEXT 容量按 UTF-8 字节计算；原事件仍保留在 Inbox，不能截断后制造业务失败。 */
    private static String textColumn(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw invalid("Failure message exceeds UTF-8 TEXT capacity");
        }
        return value;
    }

    private static String optionalTextColumn(JsonNode value, String field) {
        return value.hasNonNull(field) ? textColumn(string(value, field)) : null;
    }

    public static String uuid(JsonNode value, String field) {
        String result = string(value, field);
        if (!Ids.require(result).equals(result)) {
            throw invalid("Canonical UUID required");
        }
        return result;
    }

    private static String optionalUuid(JsonNode value, String field) {
        return value.hasNonNull(field) ? uuid(value, field) : null;
    }

    public static long positive(JsonNode value, String field) {
        long n = nonnegative(value, field);
        if (n == 0) {
            throw invalid("Positive " + field);
        }
        return n;
    }

    public static long nonnegative(JsonNode value, String field) {
        if (!value.path(field).isIntegralNumber() || !value.path(field).canConvertToLong()) {
            throw invalid("Integer " + field);
        }
        long n = value.path(field).asLong();
        if (n <0) {
            throw invalid("Nonnegative " + field);
        }
        return n;
    }

    private static Long optionalNumber(JsonNode value, String field) {
        return value.hasNonNull(field) ? positive(value, field) : null;
    }

    public static JsonNode object(JsonNode value, String field) {
        JsonNode n = value.path(field);
        if (!n.isObject()) {
            throw invalid("Object " + field);
        }
        return n;
    }

    private static JsonNode array(JsonNode value, String field) {
        JsonNode n = value.path(field);
        if (!n.isArray()) {
            throw invalid("Array " + field);
        }
        return n;
    }

    private static String timestampString(JsonNode value, String field) {
        String result = string(value, field);
        Instant.parse(result);
        return result;
    }

    /** 来源日志时间必须可无损落入 DATETIME(6)；合法原字符串保留在完整信封中。 */
    private static String sourceTimestampString(JsonNode value, String field) {
        String result = timestampString(value, field);
        if (Instant.parse(result).getNano() % 1000 != 0) {
            throw invalid("Source timestamp exceeds microsecond precision");
        }
        return result;
    }

    private static Instant optionalTime(JsonNode value, String field) {
        if (!value.hasNonNull(field)) {
            return null;
        }
        Instant result = Instant.parse(string(value, field));
        if (result.getNano() % 1000 != 0) {
            throw invalid("Source timestamp exceeds microsecond precision");
        }
        return result;
    }

    private static String oneOf(String value, String ... allowed) {
        if (!List.of(allowed).contains(value)) {
            throw invalid("Invalid enum value");
        }
        return value;
    }

    /** 声明模式之外的字段必须拒绝，不能在受理后无声丢失已删除字段。 */
    private static void only(JsonNode value, String... declared) {
        if (value == null || !value.isObject()) {
            throw invalid("Expected object");
        }
        java.util.Set<String> allowed = java.util.Set.of(declared);
        for (java.util.Map.Entry<String, JsonNode> property : value.properties()) {
            if (!allowed.contains(property.getKey())) {
                throw invalid("Unexpected field: " + property.getKey());
            }
        }
    }

    private static String boundedOptional(JsonNode value, String field, int maximum) {
        String result = optional(value, field);
        if (result != null && result.length() > maximum) {
            throw invalid("Oversized " + field);
        }
        return result;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
