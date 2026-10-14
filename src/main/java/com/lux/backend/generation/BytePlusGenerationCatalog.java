package com.lux.backend.generation;

import java.time.Instant;
import java.util.List;
import static com.lux.backend.generation.GenerationPlanContract.*;

/** 维护首批 BytePlus 模型的精确绑定与受控能力子集；目录和执行路由使用同一份定义。 */
final class BytePlusGenerationCatalog {
    /** 这是公开规范的核对时间，不代表真实账号已经完成供应商调用验证。 */
    private static final Instant CHECKED_AT = Instant.parse("2026-10-13T15:33:02Z");
    /** 产品限制每次提交的任务数量，独立于供应商单次请求生成一份结果的能力。 */
    private static final int MAXIMUM_TASKS = 32;

    private BytePlusGenerationCatalog() { }

    /** 未启用时仍提供可发现的目录，但业务校验拒绝提交这些模型。 */
    static ModelCatalogSnapshot snapshot(boolean enabled) {
        return new ModelCatalogSnapshot("model_catalog_snapshot", 2L,
                new GenerationSubmissionPolicy(MAXIMUM_TASKS), List.of(
                image("seedream-4.5", "Seedream 4.5", "seedream-4-5-251128", enabled, 14,
                        List.of(ImageOutputFormat.JPEG), ImageOutputFormat.JPEG,
                        List.of(square("2k", 2048), square("4k", 4096))),
                image("seedream-5.0-lite", "Seedream 5.0 Lite", "seedream-5-0-260128", enabled, 14,
                        List.of(ImageOutputFormat.PNG, ImageOutputFormat.JPEG), ImageOutputFormat.PNG,
                        List.of(square("2k", 2048), square("4k", 4096))),
                image("seedream-5.0-pro", "Seedream 5.0 Pro", "dola-seedream-5-0-pro-260628", enabled, 10,
                        List.of(ImageOutputFormat.PNG, ImageOutputFormat.JPEG), ImageOutputFormat.PNG,
                        List.of(square("1k", 1024), square("1.5k", 1536), square("2k", 2048))),
                video(enabled)));
    }

    /** 只有显式部署开关开启后才声明适配器与字节核验器可用，禁止按模型名称隐式回退。 */
    static List<GenerationExecutionSupport.Route> routes(boolean enabled) {
        if (!enabled) return List.of();
        return snapshot(true).models().stream().map(model -> new GenerationExecutionSupport.Route(
                model instanceof ImageModelDefinition ? MediaType.IMAGE : MediaType.VIDEO,
                new ModelSelection(model.modelId(), "standard", model.capabilityRevision()),
                model.providerBinding(), 1, true, true)).toList();
    }

    /** 图像版本分别限制编码和引用数量，不能把较新版本的能力扩展到 4.5。 */
    private static ImageModelDefinition image(String id, String name, String providerModel,
            boolean enabled, int referenceCount, List<ImageOutputFormat> formats,
            ImageOutputFormat defaultFormat, List<SizePreset> presets) {
        VerifiedImageProfile profile = new VerifiedImageProfile("verified_image_profile", "standard", ImageMode.IMAGE,
                new PromptRules(4000), new SizeRules(presets, null), references(referenceCount, false), formats,
                new ImageDefaults(new RatioSize("ratio_size", new AspectRatio(1, 1), "2k"), defaultFormat),
                evidence("https://docs.byteplus.com/en/docs/modelark/seedream-4-0-5-0"));
        return new ImageModelDefinition("image_model_definition", id, name, 1L, enabled,
                new ProviderBinding("byteplus", providerModel, "byteplus-seedream-v1"), List.of(profile));
    }

    /** 首批视频使用固定 720p 预设和整秒时长；音轨存在性及容器类型仍须检查实际输出字节。 */
    private static VideoModelDefinition video(boolean enabled) {
        List<SizePreset> presets = List.of(
                new SizePreset("720p-16:9", new AspectRatio(16, 9), "720p", new PixelDimensions(1280, 720)),
                new SizePreset("720p-9:16", new AspectRatio(9, 16), "720p", new PixelDimensions(720, 1280)),
                new SizePreset("720p-1:1", new AspectRatio(1, 1), "720p", new PixelDimensions(960, 960)));
        List<VideoOutputOption> durations = presets.stream().map(preset -> new VideoOutputOption(
                preset.presetId(), new SteppedIntDomain("stepped_int_domain", 4, 30, 1))).toList();
        VerifiedVideoProfile profile = new VerifiedVideoProfile("verified_video_profile", "standard", VideoMode.VIDEO,
                new PromptRules(4000), new SizeRules(presets, null), durations, references(30, true),
                List.of(VideoOutputFormat.MP4, VideoOutputFormat.MOV), List.of(false, true), 500L,
                new VideoDefaults(new RatioSize("ratio_size", new AspectRatio(16, 9), "720p"),
                        VideoOutputFormat.MP4, 5, false),
                evidence("https://docs.byteplus.com/en/docs/modelark/create-video-generation-task-api"));
        return new VideoModelDefinition("video_model_definition", "seedance-2.5", "Seedance 2.5", 1L, enabled,
                new ProviderBinding("byteplus", "dreamina-seedance-2-5-260628", "byteplus-seedance-v1"), List.of(profile));
    }

    /** 引用比例在 Java 提交前和供应商适配器边界均核验；请求总大小仍由适配器预检。 */
    private static ReferenceRules references(int count, boolean video) {
        PixelBounds bounds = video ? new PixelBounds(300, 6000, 300, 6000, 36_000_000L) : null;
        // 每份上限按最大引用数保留 Base64 膨胀和 JSON 开销，保证合法计划不越过 64 MiB 请求边界。
        long maximumBytes = video ? 1536L * 1024 : count == 10 ? 4L * 1024 * 1024 : 3L * 1024 * 1024;
        AspectRatioRange ratioRange = video ? new AspectRatioRange(new AspectRatio(2, 5), new AspectRatio(5, 2)) : null;
        return new ReferenceRules(0, count, List.of(new ReferenceMediaLimit(ReferenceMediaType.IMAGE, 0, count,
                List.of("image/png", "image/jpeg"), maximumBytes, bounds, null, ratioRange)), null, null, null);
    }

    private static SizePreset square(String resolution, int pixels) {
        return new SizePreset(resolution + "-square", new AspectRatio(1, 1), resolution, new PixelDimensions(pixels, pixels));
    }

    /** 明确区分供应商公开能力和产品首次开放范围，避免把有限预设声称为完整支持。 */
    private static List<CapabilityEvidence> evidence(String reference) {
        return List.of(new CapabilityEvidence(CapabilityEvidenceSourceKind.OFFICIAL_DOCUMENTATION, reference,
                CHECKED_AT, "Version-specific output encoding, reference count, and size/duration capability"),
                new CapabilityEvidence(CapabilityEvidenceSourceKind.PRODUCT_POLICY, "lux:byteplus-initial-scope",
                        CHECKED_AT, "Limited presets; one output per task; 4000 UTF-8 prompt bytes; PNG/JPEG references limited by total inline transport budget; 500 ms video tolerance"));
    }
}
