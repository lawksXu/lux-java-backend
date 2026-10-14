package com.lux.backend.chat;

import com.lux.backend.asset.AssetRepository;
import com.lux.backend.common.HttpIdempotency;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.generation.GenerationPlanCodec;
import com.lux.backend.generation.GenerationPlanOperationIdentity;
import com.lux.backend.infrastructure.EventProtocol;
import com.lux.backend.task.TaskRecord;
import com.lux.backend.task.TaskRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.lux.backend.chat.ConversationContent.*;
import static com.lux.backend.generation.GenerationPlanContract.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationPlanConversationTests {
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final GenerationPlanCodec codec = new GenerationPlanCodec();
    private final String user = "00000000-0000-0000-0000-000000000001";
    private final String session = "00000000-0000-0000-0000-000000000002";
    private final String operation = "00000000-0000-0000-0000-000000000301";
    private final String hash = "0".repeat(64);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final HttpIdempotency operations = mock(HttpIdempotency.class);
    private final ContentValidator validator = new ContentValidator(mock(AssetRepository.class), tasks, operations, codec);

    @Test void allEightCanonicalCallsKeepNamedArgumentsAndRejectUnknownFields() throws Exception {
        int count = 0;
        for (JsonNode fixture : fixtures()) {
            JsonNode invocation = fixture.path("value");
            if (!"generation_plan_tool_invocation".equals(invocation.path("type").asString())) continue;
            String tool = invocation.path("tool_name").asString();
            ObjectNode payload = JsonMapper.builder().build().createObjectNode();
            payload.set("arguments", invocation.path("arguments"));
            PlanCallContent call = (PlanCallContent) EventProtocol.payload("TOOL_CALL", tool, payload);
            assertThat(call.invocation(UUID.fromString(invocation.path("operation_id").asString()), UUID.fromString(session)))
                .isEqualTo(codec.decodeInvocation(invocation));
            assertThat(json.read(json.write(call))).isEqualTo(payload);
            ObjectNode invalid = payload.deepCopy();
            invalid.withObject("arguments").put("unknown", "reject");
            assertThatThrownBy(() -> EventProtocol.payload("TOOL_CALL", tool, invalid)).isInstanceOf(IllegalArgumentException.class);
            count++;
        }
        assertThat(count).isEqualTo(8);
    }

    @Test void canonicalResultWrapperPreservesTheUnwrappedWireShape() throws Exception {
        GenerationPlan draft = codec.decodePlan(fixtures().get(9).path("value").toString());
        GenerationPlanSnapshot snapshot = new GenerationPlanSnapshot(draft, new PlanValidation(false, List.of(), 1L));
        List<GenerationPlanToolResult> results = List.of(
            new ModelCatalogSnapshot("model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32), List.of()),
            new GenerationPlanReadResult("generation_plan_read_result", focus(), List.of(snapshot), null),
            new PlanMutationReceipt("generation_plan_mutation_receipt", PlanMutationReceiptAction.CREATED, null, snapshot, focus(),
                List.of(), List.of(), List.of()),
            submission(),
            new PlanFocusReceipt("generation_plan_focus_receipt", focus()),
            new PlanToolRejection("generation_plan_tool_rejection", PlanToolErrorCode.INVALID_ARGUMENT,
                "Invalid input", null, null, List.of(), "NOT_PERFORMED"));
        for (GenerationPlanToolResult result : results) {
            JsonNode wire = json.read(codec.write(result));
            assertThat(EventProtocol.payload("TOOL_RESULT", null, wire)).isEqualTo(new PlanResultContent(result));
            assertThat(json.read(json.write(new PlanResultContent(result)))).isEqualTo(wire);
        }
    }

    @Test void readOnlyHistoricalResultMustMatchOriginalReceiptExactly() {
        GetModelCatalogCallContent call = new GetModelCatalogCallContent(new GetModelCatalogArguments(null));
        ModelCatalogSnapshot original = new ModelCatalogSnapshot("model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32), List.of());
        store(call, original);
        assertThatNoException().isThrownBy(() -> validator.validateToolPair(user, session, operation, call, new PlanResultContent(original)));
        ModelCatalogSnapshot altered = new ModelCatalogSnapshot("model_catalog_snapshot", 2L, new GenerationSubmissionPolicy(32), List.of());
        assertThatThrownBy(() -> validator.validateToolPair(user, session, operation, call, new PlanResultContent(altered)))
            .hasMessageContaining("original operation receipt");
    }

    @Test void originalParentReceiptAcceptsIndependentChildrenButRejectsForgedPlanMembership() throws Exception {
        PlanTaskSubmissionReceipt result = submission();
        SubmitGenerationPlanCallContent call = new SubmitGenerationPlanCallContent(new SubmitGenerationPlanArguments(result.planId(), 2L, 1L));
        store(call, result);
        SubmittedTask child = result.tasks().get(0);
        when(tasks.get(user, child.taskId().toString())).thenReturn(task(child, result.planId().toString()));
        assertThatNoException().isThrownBy(() -> validator.validateToolPair(user, session, operation, call, new PlanResultContent(result)));
        when(tasks.get(user, child.taskId().toString())).thenReturn(task(child, UUID.randomUUID().toString()));
        assertThatThrownBy(() -> validator.validateToolPair(user, session, operation, call, new PlanResultContent(result)))
            .hasMessageContaining("original plan/operation");
    }

    @Test void sourceResultsRejectWrongSessionAndRepeatedChildIdentities() throws Exception {
        assertThatThrownBy(() -> validator.validateSourcePayload(user, session, new PlanResultContent(
            new PlanFocusReceipt("generation_plan_focus_receipt", new SessionPlanFocus(UUID.randomUUID(), null, 0L)))))
            .hasMessageContaining("another Session");
        PlanTaskSubmissionReceipt single = submission();
        SubmittedTask child = single.tasks().get(0);
        when(tasks.get(user, child.taskId().toString())).thenReturn(task(child, single.planId().toString()));
        PlanTaskSubmissionReceipt repeated = new PlanTaskSubmissionReceipt(single.type(), single.planId(), 2L, true, 2,
            List.of(child, new SubmittedTask(1, child.taskId(), child.taskOperationId())), focus());
        assertThatThrownBy(() -> validator.validateSourcePayload(user, session, new PlanResultContent(repeated)))
            .hasMessageContaining("ordered unique");
    }

    private void store(PlanCallContent call, GenerationPlanToolResult result) {
        JavaPlanToolInvocation invocation = call.invocation(UUID.fromString(operation), UUID.fromString(session));
        when(operations.requestHash("generation-plan-tools-v1", new GenerationPlanOperationIdentity(UUID.fromString(user), invocation))).thenReturn(hash);
        JavaPlanToolOperationReceipt receipt = new JavaPlanToolOperationReceipt("generation_plan_tool_operation_receipt", 1,
            UUID.fromString(operation), UUID.fromString(session), GenerationPlanToolName.valueOf(invocation.toolName()), hash, result);
        when(operations.originalResponse(user, operation)).thenReturn(Optional.of(codec.write(receipt)));
    }

    private SessionPlanFocus focus() { return new SessionPlanFocus(UUID.fromString(session), null, 0L); }
    private JsonNode fixtures() throws Exception { return json.read(Files.readString(Path.of("docs/fixtures/generation-plan-v1/examples.json"))); }
    private PlanTaskSubmissionReceipt submission() throws Exception {
        for (JsonNode fixture : fixtures()) {
            if ("single-file submission receipt shape only".equals(fixture.path("name").asString()))
                return (PlanTaskSubmissionReceipt) codec.decodeToolResult(fixture.path("value").path("result"));
        }
        throw new IllegalStateException("Missing submission fixture");
    }
    private TaskRecord task(SubmittedTask child, String plan) {
        return new TaskRecord(child.taskId().toString(), user, session, child.taskOperationId().toString(), "IMAGE", null,
            "SUCCEEDED", 7L, 0L, null, null, null, null, null, null, null, plan);
    }
}
