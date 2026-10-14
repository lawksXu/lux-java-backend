package com.lux.backend.generation;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.lux.backend.generation.GenerationPlanContract.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证生产模型目录的版本边界、默认关闭和供应商预设语义。 */
class BytePlusCatalogTests {
    /** 使用可信引用元数据检查边界比例；提交冻结之前拒绝供应商无法处理的引用。 */
    @Test
    void seedanceReferenceRatiosAreInclusiveAndRejectBeforeFreeze() {
        GenerationPlanDomain domain = new GenerationPlanDomain(new ModelCatalog(true));
        GenerationExecutionSupport support = new GenerationExecutionSupport(true);
        UUID asset = UUID.randomUUID();
        List<ReferenceAsset> references = List.of(new ReferenceAsset("reference_asset", asset));
        CreateVideoPlanArguments arguments = new CreateVideoPlanArguments(
                new VideoTarget("VIDEO", new ModelChoice("seedance-2.5", "standard", 1L)),
                new VideoIntent("video_intent", "Validate reference ratio",
                        new VideoRequirements("video_requirements", null, null, 1, null, null, null, null, null)),
                new VideoParameters("video_parameters", "A geometric scene", null, references, null, null, null), 0L);
        PlanConfiguration configuration = domain.create(arguments).configuration();
        Instant now = Instant.parse("2026-10-13T15:33:02Z");
        GenerationPlan plan = new GenerationPlan("generation_plan", 1, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1L, PlanState.DRAFT, configuration, null, now, now, null);
        for (PixelDimensions size : List.of(new PixelDimensions(300, 750), new PixelDimensions(750, 300),
                new PixelDimensions(300, 751), new PixelDimensions(751, 300))) {
            GenerationPlanDomain.AdmissionFacts facts = new GenerationPlanDomain.AdmissionFacts(
                    Map.of(asset, new VerifiedImageMetadata("verified_image_metadata", asset, "0".repeat(64),
                            1000L, "image/png", size)), Set.of(), support.execution(configuration), support.verifiers(configuration));
            boolean accepted = size.width() <= 750 && size.height() <= 750;
            assertEquals(accepted, domain.validation(configuration, facts).canSubmit(), size.toString());
            if (accepted) assertDoesNotThrow(() -> domain.freezeRequest(plan, facts));
            else {
                assertTrue(domain.validation(configuration, facts).issues().stream()
                        .anyMatch(issue -> issue.code() == IssueCode.REFERENCE_LIMIT_EXCEEDED));
                assertThrows(GenerationPlanDomain.DomainException.class, () -> domain.freezeRequest(plan, facts));
            }
        }
    }

    /** 实际 Java 序列化输出作为 Python 适配器的离线契约输入，不声明外部服务已经验证。 */
    @Test
    void enabledRoutesFreezeOneFileRequestsAndExportCrossProjectFixtures() throws Exception {
        ModelCatalog catalog = new ModelCatalog(true);
        GenerationPlanDomain domain = new GenerationPlanDomain(catalog);
        GenerationExecutionSupport support = new GenerationExecutionSupport(true);
        GenerationExecutionSupport disabled = new GenerationExecutionSupport();
        GenerationPlanCodec codec = new GenerationPlanCodec();
        Path directory = Path.of("target", "integration", "byteplus-requests");
        Files.createDirectories(directory);
        for (ModelDefinition model : catalog.snapshot().models()) {
            ModelChoice choice = new ModelChoice(model.modelId(), "standard", 1L);
            CreateGenerationPlanArguments arguments;
            if (model instanceof ImageModelDefinition) {
                arguments = new CreateImagePlanArguments(new ImageTarget("IMAGE", choice),
                        new ImageIntent("image_intent", "Offline contract fixture",
                                new ImageRequirements("image_requirements", null, null, 2, List.of(), null, null)),
                        new ImageParameters("image_parameters", "A blue geometric circle", null, List.of(), null), 0L);
            } else {
                arguments = new CreateVideoPlanArguments(new VideoTarget("VIDEO", choice),
                        new VideoIntent("video_intent", "Offline contract fixture",
                                new VideoRequirements("video_requirements", null, null, 2, List.of(), null, null, null, null)),
                        new VideoParameters("video_parameters", "A blue geometric circle moves", null, List.of(), null, null, null), 0L);
            }
            PlanConfiguration configuration = domain.create(arguments).configuration();
            assertEquals(Set.of(model.providerBinding()), support.execution(configuration));
            assertEquals(Set.of(model.providerBinding()), support.verifiers(configuration));
            assertTrue(disabled.execution(configuration).isEmpty());
            GenerationPlanDomain.AdmissionFacts facts = new GenerationPlanDomain.AdmissionFacts(
                    Map.of(), Set.of(), support.execution(configuration), support.verifiers(configuration));
            assertTrue(domain.validation(configuration, facts).canSubmit());
            Instant now = Instant.parse("2026-10-13T15:33:02Z");
            GenerationPlan plan = new GenerationPlan("generation_plan", 1, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), 1L, PlanState.DRAFT, configuration, null, now, now, null);
            NormalizedGenerationRequest request = domain.freezeRequest(plan, facts);
            assertEquals(model.providerBinding(), request.providerBinding());
            assertEquals(2, domain.requestedFileCount(configuration));
            if (request instanceof ImageGenerationRequest image) {
                assertEquals(1, image.resultExpectations().fileCount());
                assertEquals(new PixelDimensions(2048, 2048), ((ResolvedPresetSize) image.parameters().size()).preset().pixelDimensions());
            } else {
                VideoGenerationRequest video = (VideoGenerationRequest) request;
                assertEquals(1, video.resultExpectations().fileCount());
                assertEquals(new PixelDimensions(1280, 720), video.parameters().size().preset().pixelDimensions());
                assertEquals(5, video.parameters().durationSeconds());
                assertFalse(video.parameters().generateAudio());
            }
            String json = codec.write(request);
            assertEquals(request, codec.read(json, NormalizedGenerationRequest.class));
            Files.writeString(directory.resolve(model.modelId() + ".json"), json);
            GenerationPlanDomain disabledDomain = new GenerationPlanDomain(new ModelCatalog());
            assertTrue(disabledDomain.validation(configuration, facts).issues().stream()
                    .anyMatch(issue -> issue.code() == IssueCode.MODEL_DISABLED));
            assertThrows(GenerationPlanDomain.DomainException.class, () -> disabledDomain.freezeRequest(plan, facts));
        }
        assertTrue(support.requiresMediaProbe());
        assertFalse(disabled.requiresMediaProbe());
    }

    @Test
    void selectedModelsAreListedWithExactBindingsAndRemainDisabledByDefault() {
        ModelCatalogSnapshot catalog = new ModelCatalog().snapshot();
        assertEquals(List.of("seedream-4.5", "seedream-5.0-lite", "seedream-5.0-pro", "seedance-2.5"),
                catalog.models().stream().map(ModelDefinition::modelId).toList());
        assertTrue(catalog.models().stream().noneMatch(ModelDefinition::enabled));
        assertEquals(List.of("seedream-4-5-251128", "seedream-5-0-260128", "dola-seedream-5-0-pro-260628", "dreamina-seedance-2-5-260628"),
                catalog.models().stream().map(model -> model.providerBinding().providerModelId()).toList());
        assertTrue(catalog.models().stream().allMatch(model -> model.providerBinding().providerId().equals("byteplus")));
        assertEquals(32, catalog.submissionPolicy().maximumTasksPerSubmission());
    }

    @Test
    void imageVersionsPreserveTheirEncodingAndReferenceLimits() {
        List<ModelDefinition> models = new ModelCatalog().snapshot().models();
        assertEquals(4, models.size());
        VerifiedImageProfile old = (VerifiedImageProfile) ((ImageModelDefinition) models.get(0)).profiles().get(0);
        VerifiedImageProfile lite = (VerifiedImageProfile) ((ImageModelDefinition) models.get(1)).profiles().get(0);
        VerifiedImageProfile pro = (VerifiedImageProfile) ((ImageModelDefinition) models.get(2)).profiles().get(0);
        assertEquals(List.of(ImageOutputFormat.JPEG), old.outputFormats());
        assertEquals(List.of(ImageOutputFormat.PNG, ImageOutputFormat.JPEG), lite.outputFormats());
        assertEquals(List.of(ImageOutputFormat.PNG, ImageOutputFormat.JPEG), pro.outputFormats());
        assertEquals(14, old.referenceRules().maximumTotalCount());
        assertEquals(14, lite.referenceRules().maximumTotalCount());
        assertEquals(10, pro.referenceRules().maximumTotalCount());
        assertEquals(3L * 1024 * 1024, old.referenceRules().mediaLimits().get(0).maxAssetBytes());
        assertEquals(3L * 1024 * 1024, lite.referenceRules().mediaLimits().get(0).maxAssetBytes());
        assertEquals(4L * 1024 * 1024, pro.referenceRules().mediaLimits().get(0).maxAssetBytes());
        VerifiedVideoProfile video = (VerifiedVideoProfile) ((VideoModelDefinition) models.get(3)).profiles().get(0);
        assertEquals(1536L * 1024, video.referenceRules().mediaLimits().get(0).maxAssetBytes());
        for (ReferenceRules rules : List.of(old.referenceRules(), lite.referenceRules(), pro.referenceRules(), video.referenceRules())) {
            long encodedMaximum = rules.maximumTotalCount() * 4 * ((rules.mediaLimits().get(0).maxAssetBytes() + 2) / 3);
            assertTrue(encodedMaximum + 1024 * 1024 < 64L * 1024 * 1024);
        }
        assertEquals(ImageOutputFormat.JPEG, old.defaults().outputFormat());
        assertEquals(ImageOutputFormat.PNG, pro.defaults().outputFormat());
        assertEquals(List.of(1024, 1536, 2048), pro.sizeRules().presets().stream()
                .map(preset -> preset.pixelDimensions().width()).toList());
    }

    @Test
    void documentedLogicalRatioCanRetainRoundedNativeDimensions() {
        SizePreset preset = new SizePreset("480p-16:9", new AspectRatio(16, 9), "480p", new PixelDimensions(854, 480));
        VerifiedVideoProfile profile = new VerifiedVideoProfile("verified_video_profile", "standard", VideoMode.VIDEO,
                new PromptRules(4000), new SizeRules(List.of(preset), null),
                List.of(new VideoOutputOption(preset.presetId(), new SteppedIntDomain("stepped_int_domain", 4, 30, 1))),
                new ReferenceRules(0, 0, List.of(), null, null, null), List.of(VideoOutputFormat.MP4), List.of(false),
                500L, new VideoDefaults(new RatioSize("ratio_size", new AspectRatio(16, 9), "480p"), VideoOutputFormat.MP4, 5, false),
                List.of(new CapabilityEvidence(CapabilityEvidenceSourceKind.OFFICIAL_DOCUMENTATION,
                        "https://docs.byteplus.com/en/docs/modelark/create-video-generation-task-api", Instant.parse("2026-10-13T00:00:00Z"), "Documented encoded dimensions")));
        VideoModelDefinition model = new VideoModelDefinition("video_model_definition", "rounded-video", "Rounded video", 1L,
                false, new ProviderBinding("byteplus", "dreamina-seedance-2-5-260628", "byteplus-seedance-v1"), List.of(profile));
        ModelCatalog catalog = assertDoesNotThrow(() -> new ModelCatalog(new ModelCatalogSnapshot(
                "model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32), List.of(model))));
        assertEquals(new PixelDimensions(854, 480), ((VerifiedVideoProfile) ((VideoModelDefinition) catalog.snapshot().models().get(0))
                .profiles().get(0)).sizeRules().presets().get(0).pixelDimensions());
    }
}
