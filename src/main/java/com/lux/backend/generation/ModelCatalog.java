package com.lux.backend.generation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.lux.backend.generation.GenerationPlanContract.*;

/** Java 管理的能力目录；选定供应商的能力默认关闭，平台批次限制独立于模型能力。 */
@Component
public final class ModelCatalog {
    private final ModelCatalogSnapshot catalog;

    public ModelCatalog() { this(false); }

    /** 部署方确认 Connector 与核验基础设施就绪后显式启用，目录和路由共用该开关。 */
    @Autowired
    public ModelCatalog(@Value("${lux.generation.byteplus.enabled:false}") boolean enabled) {
        this(BytePlusGenerationCatalog.snapshot(enabled));
    }

    /** 显式目录用于受控配置与合成测试，构造时拒绝自相矛盾的注册。 */
    public ModelCatalog(ModelCatalogSnapshot catalog) {
        GenerationPlanCodec codec = new GenerationPlanCodec();
        codec.read(codec.write(catalog), ModelCatalogSnapshot.class);
        Set<String> keys = new HashSet<>();
        for (ModelDefinition model : catalog.models()) {
            MediaType media = model instanceof ImageModelDefinition ? MediaType.IMAGE : MediaType.VIDEO;
            require(keys.add(media + ":" + model.modelId()), "Duplicate model media key");
            Set<String> profiles = new HashSet<>();
            if (model instanceof ImageModelDefinition image) for (ImageProfile profile : image.profiles()) {
                require(profiles.add(profile.profileId()), "Duplicate profile");
                if (profile instanceof VerifiedImageProfile verified) {
                    validateRules(verified.sizeRules(), verified.referenceRules());
                    validateEvidence(verified.evidence());
                    validateDefaultSize(verified.defaults().size(), verified.sizeRules(), false);
                    require(verified.defaults().outputFormat() == null || verified.outputFormats().contains(verified.defaults().outputFormat()), "Unsupported default format");
                    require(new HashSet<>(verified.outputFormats()).size() == verified.outputFormats().size(), "Duplicate formats");
                }
            }
            else if (model instanceof VideoModelDefinition video) for (VideoProfile profile : video.profiles()) {
                require(profiles.add(profile.profileId()), "Duplicate profile");
                if (profile instanceof VerifiedVideoProfile verified) {
                    validateRules(verified.sizeRules(), verified.referenceRules());
                    validateEvidence(verified.evidence());
                    require(verified.sizeRules().customPixels() == null, "Video custom pixels unavailable in v1");
                    validateDefaultSize(verified.defaults().size(), verified.sizeRules(), true);
                    require(verified.defaults().outputFormat() == null || verified.outputFormats().contains(verified.defaults().outputFormat()), "Unsupported default format");
                    require(verified.defaults().generateAudio() == null || verified.generateAudioValues().contains(verified.defaults().generateAudio()), "Unsupported default audio");
                    require(new HashSet<>(verified.generateAudioValues()).size() == verified.generateAudioValues().size(), "Duplicate audio values");
                    require(new HashSet<>(verified.outputFormats()).size() == verified.outputFormats().size(), "Duplicate formats");
                    Set<String> optionKeys = new HashSet<>();
                    for (VideoOutputOption option : verified.outputOptions()) {
                        require(optionKeys.add(option.sizePresetId()), "Duplicate size duration option");
                        require(verified.sizeRules().presets().stream().anyMatch(p -> p.presetId().equals(option.sizePresetId())), "Unknown video preset");
                        validateDomain(option.durationSeconds());
                    }
                    for (SizePreset preset : verified.sizeRules().presets()) require(optionKeys.contains(preset.presetId()), "Video preset has no duration option");
                    Integer duration = verified.defaults().durationSeconds();
                    if (duration != null) {
                        SizePreset preset = preset(verified.defaults().size(), verified.sizeRules());
                        require(verified.outputOptions().stream().anyMatch(o -> (preset == null || o.sizePresetId().equals(preset.presetId())) && contains(o.durationSeconds(), duration)), "Unsupported default duration");
                    }
                }
            }
        }
        this.catalog = catalog;
    }

    public ModelCatalogSnapshot snapshot(MediaType media) {
        return media == null ? catalog : new ModelCatalogSnapshot("model_catalog_snapshot", catalog.catalogRevision(), catalog.submissionPolicy(),
            catalog.models().stream().filter(m -> (m instanceof ImageModelDefinition) == (media == MediaType.IMAGE)).toList());
    }
    public ModelCatalogSnapshot snapshot() { return catalog; }

    /** 查询精确媒体、模型与版本，绝不回退到同名模型的替换能力。 */
    public ModelDefinition find(MediaType media, ModelSelection selection) {
        return catalog.models().stream().filter(m -> (m instanceof ImageModelDefinition) == (media == MediaType.IMAGE))
            .filter(m -> m.modelId().equals(selection.modelId())).findFirst().orElse(null);
    }

    static void validateEvidence(List<CapabilityEvidence> evidence) {
        require(evidence.stream().anyMatch(e -> e.sourceKind() == CapabilityEvidenceSourceKind.OFFICIAL_DOCUMENTATION || e.sourceKind() == CapabilityEvidenceSourceKind.CONTRACT_VERIFICATION), "Product policy alone does not verify provider capability");
    }
    static void validateRules(SizeRules sizes, ReferenceRules references) {
        Set<String> ids = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (SizePreset preset : sizes.presets()) {
            require(ids.add(preset.presetId()), "Duplicate preset");
            AspectRatio ratio = ratio(preset.aspectRatio());
            require(pairs.add(ratio + ":" + preset.resolutionId()), "Ambiguous ratio resolution");
            // 比例是供应商逻辑预设；编码宽高可能按对齐规则取整，输出仍按冻结的精确像素核验。
            require(preset.pixelDimensions().width() > 0 && preset.pixelDimensions().height() > 0, "Preset dimensions must be positive");
        }
        if (sizes.customPixels() != null) {
            CustomPixelRules custom = sizes.customPixels();
            validateBounds(custom.bounds());
            Set<AspectRatio> ratios = new HashSet<>();
            for (AspectRatio ratio : custom.allowedAspectRatios()) require(ratios.add(ratio(ratio)), "Duplicate custom ratio");
        }
        require(references.minimumTotalCount() <= references.maximumTotalCount(), "Invalid total reference range");
        Set<ReferenceMediaType> media = new HashSet<>();
        int min = 0; int max = 0;
        for (ReferenceMediaLimit limit : references.mediaLimits()) {
            require(media.add(limit.mediaType()), "Duplicate reference media");
            require(limit.minimumCount() <= limit.maximumCount(), "Invalid media count range");
            require(new HashSet<>(limit.mimeTypes()).size() == limit.mimeTypes().size(), "Duplicate MIME type");
            if (limit.dimensions() != null) validateBounds(limit.dimensions());
            require(limit.mediaType() != ReferenceMediaType.AUDIO || limit.dimensions() == null, "Audio dimensions invalid");
            require(limit.mediaType() != ReferenceMediaType.AUDIO || limit.aspectRatioRange() == null, "Audio aspect ratio invalid");
            if (limit.aspectRatioRange() != null) {
                AspectRatio minimum = ratio(limit.aspectRatioRange().minimum());
                AspectRatio maximum = ratio(limit.aspectRatioRange().maximum());
                require((long) minimum.widthUnits() * maximum.heightUnits() <= (long) maximum.widthUnits() * minimum.heightUnits(), "Invalid reference ratio range");
            }
            require(limit.mediaType() != ReferenceMediaType.IMAGE || limit.maxDurationMilliseconds() == null, "Image duration invalid");
            min += limit.minimumCount(); max += limit.maximumCount();
        }
        require(min <= references.maximumTotalCount() && max >= references.minimumTotalCount(), "Impossible reference cardinality");
    }
    static void validateBounds(PixelBounds bounds) {
        require(bounds.minWidth() <= bounds.maxWidth() && bounds.minHeight() <= bounds.maxHeight(), "Invalid pixel bounds");
        require((long) bounds.minWidth() * bounds.minHeight() <= bounds.maxPixels(), "Impossible pixel area");
    }
    static void validateDefaultSize(DraftOutputSize size, SizeRules rules, boolean video) {
        if (size == null) return;
        if (size instanceof RatioSize ratio) {
            require(ratio.aspectRatio() != null && ratio.resolutionId() != null && preset(size, rules) != null, "Incomplete or unsupported default size");
        } else require(!video && custom((PixelSize) size, rules), "Unsupported default custom size");
    }
    static void validateDomain(PositiveIntDomain domain) {
        if (domain instanceof DiscreteIntDomain discrete) {
            int previous = 0;
            for (Integer value : discrete.values()) { require(value > previous, "Domain values must increase"); previous = value; }
        } else if (domain instanceof SteppedIntDomain stepped) require(stepped.minimum() <= stepped.maximum(), "Invalid stepped domain");
    }
    static boolean contains(PositiveIntDomain domain, int value) {
        if (domain instanceof DiscreteIntDomain discrete) return discrete.values().contains(value);
        SteppedIntDomain stepped = (SteppedIntDomain) domain;
        return value >= stepped.minimum() && value <= stepped.maximum() && (value - stepped.minimum()) % stepped.step() == 0;
    }
    static AspectRatio ratio(AspectRatio value) {
        int a = value.widthUnits(); int b = value.heightUnits();
        require(a > 0 && b > 0, "Ratio must be positive");
        while (b != 0) { int remainder = a % b; a = b; b = remainder; }
        return new AspectRatio(value.widthUnits() / a, value.heightUnits() / a);
    }
    static SizePreset preset(DraftOutputSize size, SizeRules rules) {
        if (!(size instanceof RatioSize ratio) || ratio.aspectRatio() == null || ratio.resolutionId() == null) return null;
        return rules.presets().stream().filter(p -> ratio(p.aspectRatio()).equals(ratio(ratio.aspectRatio())) && p.resolutionId().equals(ratio.resolutionId())).findFirst().orElse(null);
    }
    static boolean possible(RatioSize size, SizeRules rules) {
        return rules.presets().stream().anyMatch(p -> (size.aspectRatio() == null || ratio(p.aspectRatio()).equals(ratio(size.aspectRatio()))) && (size.resolutionId() == null || p.resolutionId().equals(size.resolutionId())));
    }
    static boolean custom(PixelSize size, SizeRules rules) {
        CustomPixelRules custom = rules.customPixels();
        if (custom == null) return false;
        PixelDimensions d = size.dimensions(); PixelBounds b = custom.bounds();
        return d.width() >= b.minWidth() && d.width() <= b.maxWidth() && d.height() >= b.minHeight() && d.height() <= b.maxHeight()
            && d.width() % custom.widthStep() == 0 && d.height() % custom.heightStep() == 0 && (long) d.width() * d.height() <= b.maxPixels()
            && custom.allowedAspectRatios().stream().anyMatch(r -> ratio(r).equals(ratio(new AspectRatio(d.width(), d.height()))));
    }
    static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
