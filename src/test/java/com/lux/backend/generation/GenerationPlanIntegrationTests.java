package com.lux.backend.generation;

import com.lux.backend.chat.SessionService;
import com.lux.backend.common.ApiException;
import com.lux.backend.task.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.mysql.MySQLContainer;
import static com.lux.backend.generation.GenerationPlanContract.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅使用隔离 MySQL，验证真实事务、外键和回执；合成模型不执行外部生成。 */
@SpringBootTest(properties = {"lux.workers.enabled=false", "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false", "lux.storage.provider=local"})
@Import(GenerationPlanIntegrationTests.SyntheticModels.class)
class GenerationPlanIntegrationTests {
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(768L * 1024 * 1024));
    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final ProviderBinding BINDING = new ProviderBinding("synthetic", "synthetic-model", "synthetic-adapter");
    @Autowired GenerationPlanService service;
    @Autowired GenerationPlanRepository plans;
    @Autowired SessionService sessions;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean TaskService tasks;
    private String session;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        MYSQL.start();
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("lux.storage.root", () -> System.getProperty("java.io.tmpdir") + "/lux-plan-test-assets");
        properties.add("lux.storage.staging-root", () -> System.getProperty("java.io.tmpdir") + "/lux-plan-test-staging");
    }
    @AfterAll static void stop() { MYSQL.stop(); }
    @BeforeEach void session() { session = sessions.create(OWNER, "Plan persistence").id(); }

    @Test
    void persistedMutationCloneAndFocusCasPreserveOriginalReceipts() {
        CreateGenerationPlanInvocation create = creation();
        JavaPlanToolOperationReceipt original = invoke(create);
        PlanMutationReceipt created = (PlanMutationReceipt) original.result();
        UUID id = created.snapshot().plan().id();
        assertEquals(created.snapshot().plan(), plans.get(OWNER, session, id));
        KeepChange keep = new KeepChange("KEEP");
        ImagePlanPatch patch = new ImagePlanPatch("image_plan_patch", keep,
            new ImageRequirementsPatch(keep, keep, keep, keep, keep, keep),
            new ImageParametersPatch(new SetPrompt("SET", "updated prompt"), keep, keep, keep));
        PlanMutationReceipt updated = (PlanMutationReceipt) invoke(new UpdateGenerationPlanInvocation(
            "generation_plan_tool_invocation", UUID.randomUUID(), UUID.fromString(session), "update_generation_plan",
            new UpdateGenerationPlanArguments(id, 1L, 1L, patch))).result();
        assertEquals(2L, updated.snapshot().plan().revision());
        assertEquals(updated.snapshot().plan(), service.context(OWNER, session).plan().plan());
        assertEquals(original, invoke(create));
        assertEquals(original, service.receipt(OWNER, session, create.operationId().toString()));
        assertEquals(2L, plans.get(OWNER, session, id).revision());
        PlanMutationReceipt cloned = (PlanMutationReceipt) invoke(new CloneGenerationPlanInvocation(
            "generation_plan_tool_invocation", UUID.randomUUID(), UUID.fromString(session), "clone_generation_plan",
            new CloneGenerationPlanArguments(id, 2L, 1L))).result();
        assertNotEquals(id, cloned.snapshot().plan().id());
        assertEquals(updated.snapshot().plan().configuration(), cloned.snapshot().plan().configuration());
        FocusGenerationPlanInvocation stale = new FocusGenerationPlanInvocation("generation_plan_tool_invocation",
            UUID.randomUUID(), UUID.fromString(session), "focus_generation_plan", new FocusGenerationPlanArguments(id, 1L));
        JavaPlanToolOperationReceipt rejected = invoke(stale);
        assertEquals(PlanToolErrorCode.FOCUS_REVISION_CONFLICT, ((PlanToolRejection) rejected.result()).code());
        invoke(new FocusGenerationPlanInvocation("generation_plan_tool_invocation", UUID.randomUUID(), UUID.fromString(session),
            "focus_generation_plan", new FocusGenerationPlanArguments(id, 2L)));
        assertEquals(rejected, invoke(stale));
        assertEquals(3L, service.context(OWNER, session).focus().revision());
        assertEquals(2, service.read(OWNER, session, new SessionPlansQuery("list", null, 10)).plans().size());
    }

    @Test
    void batchFreezeAndReplayPersistExactChildrenOutboxesAndOneParentReceipt() {
        UUID id = ((PlanMutationReceipt) invoke(creation()).result()).snapshot().plan().id();
        SubmitGenerationPlanInvocation submit = submission(id, 1L);
        JavaPlanToolOperationReceipt original = invoke(submit);
        PlanTaskSubmissionReceipt receipt = (PlanTaskSubmissionReceipt) original.result();
        GenerationPlan frozen = plans.get(OWNER, session, id);
        assertEquals(PlanState.FROZEN, frozen.state());
        assertEquals(2L, frozen.revision());
        assertEquals(3, receipt.tasks().size());
        assertEquals(3L, receipt.tasks().stream().map(SubmittedTask::taskOperationId).distinct().count());
        for (SubmittedTask child : receipt.tasks()) {
            assertNotEquals(submit.operationId(), child.taskOperationId());
            assertEquals(child.taskOperationId().toString(), jdbc.sql("SELECT operation_id FROM task WHERE id=? AND plan_id=? AND session_id=?")
                .param(child.taskId().toString()).param(id.toString()).param(session).query(String.class).single());
            assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE session_id=? AND JSON_UNQUOTE(JSON_EXTRACT(envelope,'$.task_id'))=?")
                .param(session).param(child.taskId().toString()).query(Integer.class).single());
        }
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM http_request WHERE user_id=? AND request_id=? AND response IS NOT NULL")
            .param(OWNER).param(submit.operationId().toString()).query(Integer.class).single());
        assertEquals(original, invoke(submit));
        assertEquals(original, service.receipt(OWNER, session, submit.operationId().toString()));
        assertEquals(3, count("task"));
        PlanTaskSubmissionReceipt reuse = (PlanTaskSubmissionReceipt) invoke(submission(id, 2L)).result();
        assertEquals(2L, reuse.frozenPlanRevision());
        assertEquals(frozen, plans.get(OWNER, session, id));
        assertEquals(6, count("task"));
    }

    @Test
    void secondChildTechnicalFailureRollsBackFreezeFirstChildOutboxAndReceipt() {
        UUID id = ((PlanMutationReceipt) invoke(creation()).result()).snapshot().plan().id();
        GenerationPlan draft = plans.get(OWNER, session, id);
        int outboxes = count("outbox_event");
        SubmitGenerationPlanInvocation submit = submission(id, 1L);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 2) throw new IllegalStateException("Injected second child failure");
            return call.callRealMethod();
        }).when(AopTestUtils.<TaskService>getUltimateTargetObject(tasks)).createPlanTask(eq(OWNER), eq(session), eq(id), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> invoke(submit));
        assertEquals(2, attempts.get());
        assertEquals(draft, plans.get(OWNER, session, id));
        assertEquals(0, count("task"));
        assertEquals(outboxes, count("outbox_event"));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM http_request WHERE user_id=? AND request_id=?")
            .param(OWNER).param(submit.operationId().toString()).query(Integer.class).single());
    }

    @Test
    void competingFocusChangesWithSameRevisionCommitOnlyOneWinner() throws Exception {
        UUID first = ((PlanMutationReceipt) invoke(creation()).result()).snapshot().plan().id();
        UUID second = ((PlanMutationReceipt) invoke(new CloneGenerationPlanInvocation("generation_plan_tool_invocation",
            UUID.randomUUID(), UUID.fromString(session), "clone_generation_plan", new CloneGenerationPlanArguments(first, 1L, 1L))).result()).snapshot().plan().id();
        invoke(new CloneGenerationPlanInvocation("generation_plan_tool_invocation", UUID.randomUUID(), UUID.fromString(session),
            "clone_generation_plan", new CloneGenerationPlanArguments(second, 1L, 2L)));
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<JavaPlanToolOperationReceipt>> results = new java.util.ArrayList<>();
            for (UUID id : List.of(first, second)) results.add(executor.submit(() -> {
                assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                return invoke(new FocusGenerationPlanInvocation("generation_plan_tool_invocation", UUID.randomUUID(), UUID.fromString(session),
                    "focus_generation_plan", new FocusGenerationPlanArguments(id, 3L)));
            }));
            start.countDown();
            List<GenerationPlanToolResult> outcomes = List.of(results.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS).result(),
                results.get(1).get(30, java.util.concurrent.TimeUnit.SECONDS).result());
            assertEquals(1L, outcomes.stream().filter(PlanFocusReceipt.class::isInstance).count());
            PlanToolRejection rejected = outcomes.stream().filter(PlanToolRejection.class::isInstance)
                .map(PlanToolRejection.class::cast).findFirst().orElseThrow();
            assertEquals(PlanToolErrorCode.FOCUS_REVISION_CONFLICT, rejected.code());
            assertEquals(4L, service.context(OWNER, session).focus().revision());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void compositeSqlKeysRejectCrossSessionPlanFocusAndTaskAndCrossOwnerPlan() {
        UUID id = ((PlanMutationReceipt) invoke(creation()).result()).snapshot().plan().id();
        String other = sessions.create(OWNER, "Other session").id();
        assertThrows(RuntimeException.class, () -> jdbc.sql("UPDATE session SET active_plan_id=? WHERE id=?")
            .param(id.toString()).param(other).update());
        assertThrows(RuntimeException.class, () -> jdbc.sql("INSERT INTO task(id,user_id,session_id,operation_id,file_type,payload,status,plan_id) VALUES (?,?,?,?,'IMAGE',JSON_OBJECT(),'CREATED',?)")
            .param(UUID.randomUUID().toString()).param(OWNER).param(other).param(UUID.randomUUID().toString()).param(id.toString()).update());
        String otherOwner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO user(id,display_name) VALUES (?,'Other')").param(otherOwner).update();
        assertThrows(RuntimeException.class, () -> jdbc.sql("INSERT INTO generation_plan(id,user_id,session_id,revision,state,configuration,created_at,updated_at) VALUES (?,?,?,1,'DRAFT',JSON_OBJECT(),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))")
            .param(UUID.randomUUID().toString()).param(otherOwner).param(session).update());
        assertEquals(404, assertThrows(ApiException.class, () -> plans.get(OWNER, other, id)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> plans.get(otherOwner, session, id)).status());
    }

    private int count(String table) { return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE session_id=?").param(session).query(Integer.class).single(); }
    private JavaPlanToolOperationReceipt invoke(JavaPlanToolInvocation request) { return service.invoke(OWNER, session, request.toolName(), request); }
    private SubmitGenerationPlanInvocation submission(UUID id, long revision) { return new SubmitGenerationPlanInvocation("generation_plan_tool_invocation", UUID.randomUUID(), UUID.fromString(session), "submit_generation_plan", new SubmitGenerationPlanArguments(id, revision, 1L)); }
    private CreateGenerationPlanInvocation creation() {
        return new CreateGenerationPlanInvocation("generation_plan_tool_invocation", UUID.randomUUID(), UUID.fromString(session), "create_generation_plan",
            new CreateImagePlanArguments(new ImageTarget("IMAGE", new ModelChoice("synthetic", "profile", 1L)),
                new ImageIntent("image_intent", "test", new ImageRequirements("image_requirements", null, null, 3, null, null, null)),
                new ImageParameters("image_parameters", "prompt", null, List.of(), null), 0L));
    }
    /** 显式合成能力证据，只供本测试接纳请求，不代表任何真实模型已接通。 */
    @TestConfiguration
    static class SyntheticModels {
        @Bean @Primary ModelCatalog syntheticCatalog() {
            SizePreset size = new SizePreset("square", new AspectRatio(1, 1), "small", new PixelDimensions(64, 64));
            VerifiedImageProfile profile = new VerifiedImageProfile("verified_image_profile", "profile", ImageMode.IMAGE,
                new PromptRules(1000), new SizeRules(List.of(size), null), new ReferenceRules(0, 0, List.of(), null, null, null),
                List.of(ImageOutputFormat.PNG), new ImageDefaults(new RatioSize("ratio_size", size.aspectRatio(), size.resolutionId()), ImageOutputFormat.PNG),
                List.of(new CapabilityEvidence(CapabilityEvidenceSourceKind.CONTRACT_VERIFICATION, "synthetic-test-fixture", Instant.parse("2026-10-13T00:00:00Z"), "Synthetic only")));
            return new ModelCatalog(new ModelCatalogSnapshot("model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32),
                List.of(new ImageModelDefinition("image_model_definition", "synthetic", "Synthetic", 1L, true, BINDING, List.of(profile)))));
        }
        @Bean @Primary GenerationExecutionSupport syntheticRoutes() { return new GenerationExecutionSupport(List.of(
            new GenerationExecutionSupport.Route(MediaType.IMAGE, new ModelSelection("synthetic", "profile", 1L), BINDING, 1, true, true))); }
    }
}
