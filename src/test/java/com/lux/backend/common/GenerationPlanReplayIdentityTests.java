package com.lux.backend.common;

import com.lux.backend.asset.AssetRepository;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.generation.GenerationExecutionSupport;
import com.lux.backend.generation.GenerationMediaInspector;
import com.lux.backend.generation.GenerationPlanCodec;
import com.lux.backend.generation.GenerationPlanOperationIdentity;
import com.lux.backend.generation.GenerationPlanRepository;
import com.lux.backend.generation.GenerationPlanService;
import com.lux.backend.generation.ModelCatalog;
import com.lux.backend.task.TaskService;
import com.lux.backend.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.lux.backend.generation.GenerationPlanContract.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实幂等摘要和读取边界验证跨协议重放，数据库仅作为外部边界替身。 */
class GenerationPlanReplayIdentityTests {
    private static final String NAMESPACE = "generation-plan-tools-v1";
    private final String owner = UUID.randomUUID().toString();
    private final String session = UUID.randomUUID().toString();
    private final UUID operation = UUID.randomUUID();
    private final HttpRequestJpaRepository rows = mock(HttpRequestJpaRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final HttpIdempotency idem = new HttpIdempotency(rows, entities, new JsonCodec(JsonMapper.builder().build()), transactions);
    private final GenerationPlanRepository plans = mock(GenerationPlanRepository.class);
    private final ChatRepository chats = mock(ChatRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final GenerationMediaInspector inspector = mock(GenerationMediaInspector.class);
    private final TaskService tasks = mock(TaskService.class);
    private final GenerationPlanCodec codec = spy(new GenerationPlanCodec());
    private final ModelCatalog catalog = new ModelCatalog();
    private final GenerationPlanService service = new GenerationPlanService(plans, chats, assets, inspector, catalog,
        new GenerationExecutionSupport(List.of()), tasks, idem, mock(UserRepository.class), codec);

    @Test void unrelatedNamespaceResponseConflictsBeforePlanReceiptDecoding() {
        JavaPlanToolInvocation invocation = invocation(null);
        store(idem.requestHash("create-session", new SessionTitle("original session")), "{\"session_id\":\"" + session + "\",\"title\":\"original session\"}");
        ApiException conflict = assertThrows(ApiException.class, () -> service.invoke(owner, session, invocation.toolName(), invocation));
        assertEquals(409, conflict.status());
        verify(codec, never()).read(anyString(), eq(JavaPlanToolOperationReceipt.class));
        verifyNoInteractions(plans, chats, assets, inspector, tasks, entities, transactions);
    }

    @Test void changedPlanArgumentsConflictBeforeDecodingEvenAValidHistoricalReceipt() {
        JavaPlanToolInvocation original = invocation(null);
        String originalHash = hash(original);
        JavaPlanToolOperationReceipt receipt = receipt(original, originalHash);
        store(originalHash, codec.write(receipt));
        JavaPlanToolInvocation changed = invocation(MediaType.IMAGE);
        assertEquals(409, assertThrows(ApiException.class, () -> service.invoke(owner, session, changed.toolName(), changed)).status());
        verify(codec, never()).read(anyString(), eq(JavaPlanToolOperationReceipt.class));
        verifyNoInteractions(plans, chats, assets, inspector, tasks, entities, transactions);
    }

    @Test void matchingIdentityReplaysOriginalReceiptBeforeCurrentBusinessReads() {
        JavaPlanToolInvocation invocation = invocation(null);
        String hash = hash(invocation);
        JavaPlanToolOperationReceipt historical = receipt(invocation, hash);
        store(hash, codec.write(historical));
        assertEquals(historical, service.invoke(owner, session, invocation.toolName(), invocation));
        verifyNoInteractions(plans, chats, assets, inspector, tasks, entities, transactions);
    }

    @Test void unconditionalLookupRetainsOriginalResponseSemantics() {
        String unrelated = "{\"session_id\":\"" + session + "\"}";
        store("1".repeat(64), unrelated);
        assertEquals(Optional.of(unrelated), idem.originalResponse(owner, operation.toString()));
    }

    private record SessionTitle(String title) { }
    private JavaPlanToolInvocation invocation(MediaType media) {
        return new GetModelCatalogInvocation("generation_plan_tool_invocation", operation, UUID.fromString(session), "get_model_catalog", new GetModelCatalogArguments(media));
    }
    private String hash(JavaPlanToolInvocation invocation) {
        return idem.requestHash(NAMESPACE, new GenerationPlanOperationIdentity(UUID.fromString(owner), invocation));
    }
    private JavaPlanToolOperationReceipt receipt(JavaPlanToolInvocation invocation, String hash) {
        return new JavaPlanToolOperationReceipt("generation_plan_tool_operation_receipt", 1, operation, UUID.fromString(session), GenerationPlanToolName.get_model_catalog, hash, catalog.snapshot());
    }
    private void store(String hash, String response) {
        HttpRequestEntity receipt = new HttpRequestEntity();
        receipt.userId = owner; receipt.requestId = operation.toString(); receipt.requestHash = hash; receipt.response = response;
        when(rows.findById(new HttpRequestEntity.Id(owner, operation.toString()))).thenReturn(Optional.of(receipt));
    }
}
