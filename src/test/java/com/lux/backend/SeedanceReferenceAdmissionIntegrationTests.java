package com.lux.backend;

import com.lux.backend.asset.AssetRecord;
import com.lux.backend.asset.AssetService;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.chat.SessionService;
import com.lux.backend.common.Timestamps;
import com.lux.backend.generation.GenerationPlanDomain;
import com.lux.backend.generation.GenerationPlanRepository;
import com.lux.backend.generation.GenerationPlanService;
import com.lux.backend.generation.ModelCatalog;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lux.backend.generation.GenerationPlanContract.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 MySQL/S3 引用检查：旧草稿也必须在创建 Task 和生成 Outbox 之前拒绝非法比例。 */
@SpringBootTest(properties = "lux.generation.byteplus.enabled=true")
class SeedanceReferenceAdmissionIntegrationTests extends IntegrationSupport {
    @Autowired AssetService assets;
    @Autowired SessionService sessions;
    @Autowired ChatRepository chats;
    @Autowired GenerationPlanRepository plans;
    @Autowired GenerationPlanService service;
    @Autowired ModelCatalog catalog;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;

    @Test
    void invalidPersistedDraftCannotFreezeOrCreateTasksAndGenerationCommands() throws Exception {
        for (PixelDimensions dimensions : List.of(new PixelDimensions(300, 751), new PixelDimensions(751, 300))) {
            String session = sessions.create(USER, "Invalid reference ratio").id();
            GenerationPlan original = persistedDraft(session, upload(dimensions));
            SubmitGenerationPlanInvocation request = submission(session, original.id());
            JavaPlanToolOperationReceipt receipt = service.invoke(USER, session, request.toolName(), request);
            PlanToolRejection rejection = assertInstanceOf(PlanToolRejection.class, receipt.result());
            assertEquals(PlanToolErrorCode.PLAN_NOT_SUBMITTABLE, rejection.code());
            assertTrue(rejection.issues().stream().anyMatch(issue -> issue.code() == IssueCode.REFERENCE_LIMIT_EXCEEDED));
            assertEquals(original, plans.get(USER, session, original.id()));
            assertEquals(0, taskCount(session));
            assertEquals(0, generationCommands(session));
            assertEquals(receipt, service.invoke(USER, session, request.toolName(), request));
            assertEquals(receipt, service.receipt(USER, session, request.operationId().toString()));
        }
    }

    @Test
    void inclusiveBoundaryReferencesProduceOneFrozenTaskWithExactAssetIdentity() throws Exception {
        for (PixelDimensions dimensions : List.of(new PixelDimensions(300, 750), new PixelDimensions(750, 300))) {
            String session = sessions.create(USER, "Boundary reference ratio").id();
            AssetRecord asset = upload(dimensions);
            GenerationPlan draft = persistedDraft(session, asset);
            SubmitGenerationPlanInvocation request = submission(session, draft.id());
            PlanTaskSubmissionReceipt receipt = assertInstanceOf(PlanTaskSubmissionReceipt.class,
                    service.invoke(USER, session, request.toolName(), request).result());
            assertEquals(1, receipt.tasks().size());
            assertEquals(1, taskCount(session));
            assertEquals(1, generationCommands(session));
            GenerationPlan frozen = plans.get(USER, session, draft.id());
            assertEquals(PlanState.FROZEN, frozen.state());
            VideoGenerationRequest generation = assertInstanceOf(VideoGenerationRequest.class, frozen.frozenRequest());
            assertEquals(UUID.fromString(asset.id()), generation.parameters().references().get(0).assetId());
            assertEquals(1, generation.resultExpectations().fileCount());
        }
    }

    /** 模拟升级前已经保存的草稿，提交路径仍须根据当前可信字节重新验证。 */
    private GenerationPlan persistedDraft(String session, AssetRecord asset) {
        CreateVideoPlanArguments arguments = new CreateVideoPlanArguments(
                new VideoTarget("VIDEO", new ModelChoice("seedance-2.5", "standard", 1L)),
                new VideoIntent("video_intent", "Reference admission",
                        new VideoRequirements("video_requirements", null, null, 1, null, null, null, null, null)),
                new VideoParameters("video_parameters", "A blue geometric object moves", null,
                        List.of(new ReferenceAsset("reference_asset", UUID.fromString(asset.id()))), null, null, null), 0L);
        PlanConfiguration configuration = new GenerationPlanDomain(catalog).create(arguments).configuration();
        Instant now = Timestamps.now();
        GenerationPlan plan = new GenerationPlan("generation_plan", 1, UUID.randomUUID(), UUID.fromString(USER),
                UUID.fromString(session), 1L, PlanState.DRAFT, configuration, null, now, now, null);
        transactions.executeWithoutResult(status -> {
            chats.sessionForMutation(USER, session);
            plans.insert(plan);
            chats.focusPlan(USER, session, plan.id());
        });
        return plan;
    }

    /** 真实上传和检查实际 PNG 像素，不能用伪造元数据替代 S3/Java 边界。 */
    private AssetRecord upload(PixelDimensions dimensions) throws Exception {
        BufferedImage image = new BufferedImage(dimensions.width(), dimensions.height(), BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return assets.upload(USER, new MockMultipartFile("file", "reference.png", "image/png", bytes.toByteArray()), UUID.randomUUID().toString());
    }

    private SubmitGenerationPlanInvocation submission(String session, UUID plan) {
        return new SubmitGenerationPlanInvocation("generation_plan_tool_invocation", UUID.randomUUID(), UUID.fromString(session),
                "submit_generation_plan", new SubmitGenerationPlanArguments(plan, 1L, 1L));
    }
    private int taskCount(String session) {
        return jdbc.sql("SELECT COUNT(*) FROM task WHERE session_id=?").param(session).query(Integer.class).single();
    }
    private int generationCommands(String session) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE session_id=? AND topic='lux.generation.commands.v2'")
                .param(session).query(Integer.class).single();
    }
}
