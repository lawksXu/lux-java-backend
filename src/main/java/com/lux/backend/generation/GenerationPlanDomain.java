package com.lux.backend.generation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import static com.lux.backend.generation.GenerationPlanContract.*;

/** 无 IO 的计划规则：保留要求，归一化候选，解释默认值，并产生冻结的单文件请求。 */
@Component
public final class GenerationPlanDomain {
    private final ModelCatalog catalog;

    public GenerationPlanDomain(ModelCatalog catalog) { this.catalog = catalog; }

    /**
     * 可信边界已核验的输入事实；键值关联不代替固定业务记录。空事实默认拒绝执行。
     * 路由集合是调用者针对本次确切媒体、配置档和模式版本核验后的断言，不能仅凭绑定外形加入。
     */
    public record AdmissionFacts(Map<UUID, VerifiedMediaMetadata> metadata, Set<UUID> unavailableAssets,
                                 Set<ProviderBinding> executionAdapters, Set<ProviderBinding> resultVerifiers) {
        public AdmissionFacts {
            metadata = Map.copyOf(metadata); unavailableAssets = Set.copyOf(unavailableAssets);
            executionAdapters = Set.copyOf(executionAdapters); resultVerifiers = Set.copyOf(resultVerifiers);
            for (Map.Entry<UUID, VerifiedMediaMetadata> entry : metadata.entrySet())
                ModelCatalog.require(entry.getKey().equals(entry.getValue().assetId()), "Metadata identity mismatch");
        }
        public static AdmissionFacts unavailable() { return new AdmissionFacts(Map.of(), Set.of(), Set.of(), Set.of()); }
    }

    /** 已证明没有业务修改的域拒绝，由事务服务封装成可重放的操作回执。 */
    public static final class DomainException extends IllegalArgumentException {
        private final PlanToolErrorCode code;
        private final List<ValidationIssue> issues;
        public DomainException(PlanToolErrorCode code, String message, List<ValidationIssue> issues) {
            super(message); this.code = code; this.issues = List.copyOf(issues);
        }
        public PlanToolErrorCode code() { return code; }
        public List<ValidationIssue> issues() { return issues; }
    }

    /** 共享的内部候选结构仍明确声明每个已知字段；媒体格式通过闭合枚举表示。 */
    private enum Encoding { PNG, JPEG, MP4, MOV }
    private record Values(String prompt, DraftOutputSize size, List<ReferenceAsset> references,
                          Encoding format, Integer duration, Boolean audio) {}
    private record IntentValues(String description, Values requirements, Integer fileCount, MediaType mode) {}
    private record Target(ModelSelection selection, ProviderBinding binding,
                          VerifiedImageProfile image, VerifiedVideoProfile video) {
        SizeRules sizes() { return image != null ? image.sizeRules() : video.sizeRules(); }
        ReferenceRules references() { return image != null ? image.referenceRules() : video.referenceRules(); }
        PromptRules prompts() { return image != null ? image.promptRules() : video.promptRules(); }
    }

    public ConfigurationChange create(CreateGenerationPlanArguments arguments) { return create(arguments, AdmissionFacts.unavailable()); }
    public ConfigurationChange create(CreateGenerationPlanArguments arguments, AdmissionFacts facts) {
        PlanConfiguration configuration;
        if (arguments instanceof CreateImagePlanArguments image) {
            configuration = new ImagePlanConfiguration("image_plan_configuration", choose(MediaType.IMAGE, image.target().modelChoice()), image.intent(), image.parameters(), List.of());
        } else {
            CreateVideoPlanArguments video = (CreateVideoPlanArguments) arguments;
            configuration = new VideoPlanConfiguration("video_plan_configuration", choose(MediaType.VIDEO, video.target().modelChoice()), video.intent(), video.parameters(), List.of());
        }
        configuration = rebuild(configuration, intent(configuration), normalized(values(configuration)), explicitOrigins(values(configuration)));
        rejectUnsupportedAssignments(configuration, populated(values(configuration)), facts);
        return normalize(configuration, List.of(), List.of(), facts);
    }

    public ConfigurationChange update(PlanConfiguration configuration, PlanPatch patch) { return update(configuration, patch, AdmissionFacts.unavailable()); }
    public ConfigurationChange update(PlanConfiguration configuration, PlanPatch patch, AdmissionFacts facts) {
        PlanConfiguration updated = patch(configuration, patch);
        Set<ParameterKey> assigned = assigned(patch);
        rejectUnsupportedAssignments(updated, assigned, facts);
        return normalize(updated, List.of(), List.of(), facts);
    }
    public ConfigurationChange cloneConfiguration(PlanConfiguration configuration) {
        // 克隆是精确复制；不能刷新旧版本的默认值或路由。
        return new ConfigurationChange(configuration, List.of(), List.of(), List.of());
    }

    public ConfigurationChange retarget(PlanConfiguration source, GenerationTarget target) { return retarget(source, target, AdmissionFacts.unavailable()); }
    public ConfigurationChange retarget(PlanConfiguration source, GenerationTarget target, AdmissionFacts facts) {
        MediaType media = MediaType.valueOf(target.mediaType());
        ModelSelection selection = choose(media, target.modelChoice());
        if (media != media(source)) {
            IntentValues old = intent(source);
            AspectRatio aspect = old.requirements().size() instanceof RatioSize ratio ? ratio.aspectRatio() : null;
            DraftOutputSize size = aspect == null ? null : new RatioSize("ratio_size", aspect, null);
            List<IssueField> omitted = nonMigrated(old);
            PlanConfiguration created;
            if (media == MediaType.IMAGE) created = new ImagePlanConfiguration("image_plan_configuration", selection,
                new ImageIntent("image_intent", old.description(), new ImageRequirements("image_requirements", null, size, old.fileCount(), null, null, null)),
                new ImageParameters("image_parameters", null, null, List.of(), null), List.of());
            else created = new VideoPlanConfiguration("video_plan_configuration", selection,
                new VideoIntent("video_intent", old.description(), new VideoRequirements("video_requirements", null, size, old.fileCount(), null, null, null, null, null)),
                new VideoParameters("video_parameters", null, null, List.of(), null, null, null), List.of());
            return normalize(created, List.of(), omitted, facts);
        }
        if (Objects.equals(selection, source.modelSelection())) return new ConfigurationChange(source, List.of(), List.of(), List.of());
        PlanConfiguration selected = select(source, selection);
        Target resolved = resolve(selected, null);
        if (resolved == null) return new ConfigurationChange(selected, List.of(), List.of(), List.of());
        Values original = values(selected);
        String prompt = original.prompt(); DraftOutputSize size = original.size();
        List<ReferenceAsset> references = original.references(); Encoding format = original.format();
        Integer duration = original.duration(); Boolean audio = original.audio();
        List<ParameterKey> cleared = new ArrayList<>();
        if (prompt != null && !promptSupported(prompt, resolved)) { prompt = null; cleared.add(ParameterKey.PROMPT); }
        if (size instanceof PixelSize pixels && !ModelCatalog.custom(pixels, resolved.sizes())) { size = null; cleared.add(ParameterKey.PIXEL_DIMENSIONS); }
        else if (size instanceof RatioSize ratio) {
            AspectRatio aspect = ratio.aspectRatio(); String resolution = ratio.resolutionId();
            if (aspect != null && !ModelCatalog.possible(new RatioSize("ratio_size", aspect, null), resolved.sizes())) { aspect = null; cleared.add(ParameterKey.ASPECT_RATIO); }
            if (resolution != null && !ModelCatalog.possible(new RatioSize("ratio_size", aspect, resolution), resolved.sizes())) { resolution = null; cleared.add(ParameterKey.RESOLUTION_ID); }
            size = aspect == null && resolution == null ? null : new RatioSize("ratio_size", aspect, resolution);
        }
        List<ValidationIssue> referenceIssues = referenceIssues(references, resolved, facts, null);
        if (referenceIssues.stream().anyMatch(i -> i.code() != IssueCode.REFERENCE_METADATA_UNAVAILABLE && i.code() != IssueCode.REFERENCE_UNAVAILABLE)) {
            references = List.of(); if (!original.references().isEmpty()) cleared.add(ParameterKey.REFERENCES);
        }
        if (duration != null && (!durationSupported(duration, size, resolved) || inputDurationExceeded(references, duration, resolved, facts))) { duration = null; cleared.add(ParameterKey.DURATION_SECONDS); }
        if (format != null && !formatSupported(format, resolved)) { format = null; cleared.add(ParameterKey.OUTPUT_FORMAT); }
        if (audio != null && !audioSupported(audio, resolved)) { audio = null; cleared.add(ParameterKey.GENERATE_AUDIO); }
        Values migrated = new Values(prompt, size, references, format, duration, audio);
        PlanConfiguration changed = rebuild(selected, intent(source), migrated, retainOrigins(source.parameterOrigins(), migrated));
        return normalize(changed, cleared, List.of(), facts);
    }

    public int requestedFileCount(PlanConfiguration configuration) { Integer count = intent(configuration).fileCount(); return count == null ? 1 : count; }
    public PlanValidation validation(GenerationPlan plan) { return validation(plan, AdmissionFacts.unavailable()); }
    public PlanValidation validation(PlanConfiguration configuration) { return validation(configuration, AdmissionFacts.unavailable()); }
    public PlanValidation validation(PlanConfiguration configuration, AdmissionFacts facts) { return validate(configuration, facts, null); }
    public PlanValidation validation(GenerationPlan plan, AdmissionFacts facts) {
        if (plan.state() == PlanState.DRAFT && (plan.frozenRequest() != null || plan.frozenAt() != null)) throw new IllegalArgumentException("Draft has frozen metadata");
        if (plan.state() == PlanState.FROZEN && (plan.frozenRequest() == null || plan.frozenAt() == null)) throw new IllegalArgumentException("Frozen metadata missing");
        PlanValidation result = validate(plan.configuration(), facts, plan.frozenRequest());
        if (plan.state() == PlanState.FROZEN && result.canSubmit()) {
            NormalizedGenerationRequest expected = compile(plan.configuration());
            if (!expected.equals(plan.frozenRequest())) throw new IllegalArgumentException("Frozen request disagrees with pinned configuration");
        }
        return result;
    }
    public NormalizedGenerationRequest freezeRequest(GenerationPlan plan) { return freezeRequest(plan, AdmissionFacts.unavailable()); }
    public NormalizedGenerationRequest freezeRequest(GenerationPlan plan, AdmissionFacts facts) {
        PlanValidation validation = validation(plan, facts);
        if (!validation.canSubmit()) throw new DomainException(PlanToolErrorCode.PLAN_NOT_SUBMITTABLE, "Plan is not submittable", validation.issues());
        return plan.state() == PlanState.FROZEN ? plan.frozenRequest() : compile(plan.configuration());
    }

    /** 要求优先且只填缺项；已有冲突候选不自动改写，默认值不能覆盖要求。 */
    private ConfigurationChange normalize(PlanConfiguration configuration, List<ParameterKey> cleared,
                                          List<IssueField> omitted, AdmissionFacts facts) {
        Target target = resolve(configuration, null);
        Values candidate = normalized(values(configuration)); Values requirement = normalized(intent(configuration).requirements());
        EnumMap<ParameterKey, ParameterOrigin> origins = origins(configuration.parameterOrigins());
        List<AppliedDefault> defaults = new ArrayList<>();
        String prompt = candidate.prompt(); DraftOutputSize size = candidate.size();
        List<ReferenceAsset> references = candidate.references(); Encoding format = candidate.format();
        Integer duration = candidate.duration(); Boolean audio = candidate.audio();
        if (prompt == null && requirement.prompt() != null && (target == null || promptSupported(requirement.prompt(), target))) {
            prompt = requirement.prompt(); origin(origins, ParameterKey.PROMPT, null, defaults);
        }
        // 新要求可以撤销冲突的旧模型默认值；显式选择必须保留并产生阻碍。
        if (defaulted(origins, ParameterKey.PROMPT) && requirement.prompt() != null && !Objects.equals(prompt, requirement.prompt())) { prompt = null; origins.remove(ParameterKey.PROMPT); }
        if (defaulted(origins, ParameterKey.OUTPUT_FORMAT) && requirement.format() != null && !Objects.equals(format, requirement.format())) { format = null; origins.remove(ParameterKey.OUTPUT_FORMAT); }
        if (defaulted(origins, ParameterKey.DURATION_SECONDS) && requirement.duration() != null && !Objects.equals(duration, requirement.duration())) { duration = null; origins.remove(ParameterKey.DURATION_SECONDS); }
        if (defaulted(origins, ParameterKey.GENERATE_AUDIO) && requirement.audio() != null && !Objects.equals(audio, requirement.audio())) { audio = null; origins.remove(ParameterKey.GENERATE_AUDIO); }
        if (size instanceof RatioSize current && requirement.size() instanceof RatioSize wantedRatio) {
            AspectRatio ratio = current.aspectRatio(); String resolution = current.resolutionId();
            if (defaulted(origins, ParameterKey.ASPECT_RATIO) && wantedRatio.aspectRatio() != null && !Objects.equals(ratio, wantedRatio.aspectRatio())) { ratio = null; origins.remove(ParameterKey.ASPECT_RATIO); }
            if (defaulted(origins, ParameterKey.RESOLUTION_ID) && wantedRatio.resolutionId() != null && !Objects.equals(resolution, wantedRatio.resolutionId())) { resolution = null; origins.remove(ParameterKey.RESOLUTION_ID); }
            size = ratio == null && resolution == null ? null : new RatioSize("ratio_size", ratio, resolution);
        } else if (size instanceof PixelSize && requirement.size() instanceof RatioSize
            && defaulted(origins, ParameterKey.PIXEL_DIMENSIONS) && !sizeMatches(requirement.size(), size, target)) {
            // 自定义像素可证明比例，但不能证明目录分辨率标签；只撤销冲突的模型默认选择。
            size = null; origins.remove(ParameterKey.PIXEL_DIMENSIONS);
        } else if (requirement.size() instanceof PixelSize && !sizeMatches(requirement.size(), size, target)) {
            if (defaulted(origins, ParameterKey.PIXEL_DIMENSIONS)) { size = null; origins.remove(ParameterKey.PIXEL_DIMENSIONS); }
            else if (size instanceof RatioSize current) {
                AspectRatio ratio = defaulted(origins, ParameterKey.ASPECT_RATIO) ? null : current.aspectRatio();
                String resolution = defaulted(origins, ParameterKey.RESOLUTION_ID) ? null : current.resolutionId();
                if (ratio == null) origins.remove(ParameterKey.ASPECT_RATIO);
                if (resolution == null) origins.remove(ParameterKey.RESOLUTION_ID);
                size = ratio == null && resolution == null ? null : new RatioSize("ratio_size", ratio, resolution);
            }
        }
        DraftOutputSize wanted = requirement.size();
        if (wanted != null) {
            if (wanted instanceof PixelSize pixels && size == null) {
                if (target == null || ModelCatalog.custom(pixels, target.sizes())) {
                    size = wanted; origin(origins, ParameterKey.PIXEL_DIMENSIONS, null, defaults);
                } else {
                    SizePreset matching = target.sizes().presets().stream().filter(p -> p.pixelDimensions().equals(pixels.dimensions())).findFirst().orElse(null);
                    if (matching != null) {
                        size = new RatioSize("ratio_size", ModelCatalog.ratio(matching.aspectRatio()), matching.resolutionId());
                        origin(origins, ParameterKey.ASPECT_RATIO, null, defaults); origin(origins, ParameterKey.RESOLUTION_ID, null, defaults);
                    }
                }
            } else if (wanted instanceof RatioSize required && (size == null || size instanceof RatioSize)) {
                RatioSize current = size == null ? new RatioSize("ratio_size", null, null) : (RatioSize) size;
                AspectRatio ratio = current.aspectRatio(); String resolution = current.resolutionId();
                if (ratio == null && required.aspectRatio() != null && (target == null || ModelCatalog.possible(new RatioSize("ratio_size", required.aspectRatio(), resolution), target.sizes()))) {
                    ratio = required.aspectRatio(); origin(origins, ParameterKey.ASPECT_RATIO, null, defaults);
                }
                if (resolution == null && required.resolutionId() != null && (target == null || ModelCatalog.possible(new RatioSize("ratio_size", ratio, required.resolutionId()), target.sizes()))) {
                    resolution = required.resolutionId(); origin(origins, ParameterKey.RESOLUTION_ID, null, defaults);
                }
                size = ratio == null && resolution == null ? null : new RatioSize("ratio_size", ratio, resolution);
            }
        }
        if (references.isEmpty() && requirement.references() != null && !requirement.references().isEmpty()
            && (target == null || referenceIssues(requirement.references(), target, facts, duration).stream().allMatch(i -> i.code() == IssueCode.REFERENCE_METADATA_UNAVAILABLE || i.code() == IssueCode.REFERENCE_UNAVAILABLE))) {
            references = requirement.references(); origin(origins, ParameterKey.REFERENCES, null, defaults);
        }
        if (format == null && requirement.format() != null && (target == null || formatSupported(requirement.format(), target))) {
            format = requirement.format(); origin(origins, ParameterKey.OUTPUT_FORMAT, null, defaults);
        }
        if (duration == null && requirement.duration() != null && (target == null || durationSupported(requirement.duration(), size, target))) {
            duration = requirement.duration(); origin(origins, ParameterKey.DURATION_SECONDS, null, defaults);
        }
        if (audio == null && requirement.audio() != null && (target == null || audioSupported(requirement.audio(), target))) {
            audio = requirement.audio(); origin(origins, ParameterKey.GENERATE_AUDIO, null, defaults);
        }
        if (target != null) {
            Values modelDefaults = target.image() != null
                ? new Values(null, target.image().defaults().size(), List.of(), encoding(target.image().defaults().outputFormat()), null, null)
                : new Values(null, target.video().defaults().size(), List.of(), encoding(target.video().defaults().outputFormat()), target.video().defaults().durationSeconds(), target.video().defaults().generateAudio());
            if (modelDefaults.size() instanceof PixelSize pixels && size == null && wanted == null) {
                size = pixels; origin(origins, ParameterKey.PIXEL_DIMENSIONS, target.selection(), defaults);
            } else if (modelDefaults.size() instanceof RatioSize suggested && (size == null || size instanceof RatioSize) && !(wanted instanceof PixelSize)) {
                RatioSize current = size == null ? new RatioSize("ratio_size", null, null) : (RatioSize) size;
                RatioSize constraints = wanted instanceof RatioSize ratio ? ratio : new RatioSize("ratio_size", null, null);
                AspectRatio ratio = current.aspectRatio(); String resolution = current.resolutionId();
                if (ratio == null && constraints.aspectRatio() == null && ModelCatalog.possible(new RatioSize("ratio_size", suggested.aspectRatio(), resolution), target.sizes())) {
                    ratio = ModelCatalog.ratio(suggested.aspectRatio()); origin(origins, ParameterKey.ASPECT_RATIO, target.selection(), defaults);
                }
                if (resolution == null && constraints.resolutionId() == null && ModelCatalog.possible(new RatioSize("ratio_size", ratio, suggested.resolutionId()), target.sizes())) {
                    resolution = suggested.resolutionId(); origin(origins, ParameterKey.RESOLUTION_ID, target.selection(), defaults);
                }
                size = ratio == null && resolution == null ? null : new RatioSize("ratio_size", ratio, resolution);
            }
            if (format == null && requirement.format() == null && modelDefaults.format() != null) {
                format = modelDefaults.format(); origin(origins, ParameterKey.OUTPUT_FORMAT, target.selection(), defaults);
            }
            if (duration == null && requirement.duration() == null && modelDefaults.duration() != null && durationSupported(modelDefaults.duration(), size, target)
                && !inputDurationExceeded(references, modelDefaults.duration(), target, facts)) {
                duration = modelDefaults.duration(); origin(origins, ParameterKey.DURATION_SECONDS, target.selection(), defaults);
            }
            if (audio == null && requirement.audio() == null && modelDefaults.audio() != null) {
                audio = modelDefaults.audio(); origin(origins, ParameterKey.GENERATE_AUDIO, target.selection(), defaults);
            }
        }
        Values effective = new Values(prompt, size, references, format, duration, audio);
        IntentValues normalizedIntent = new IntentValues(intent(configuration).description(), requirement, intent(configuration).fileCount(), intent(configuration).mode());
        return new ConfigurationChange(rebuild(configuration, normalizedIntent, effective, retainOrigins(new ArrayList<>(origins.values()), effective)), cleared, omitted, defaults);
    }

    private PlanValidation validate(PlanConfiguration configuration, AdmissionFacts facts, NormalizedGenerationRequest frozen) {
        List<ValidationIssue> issues = new ArrayList<>();
        validateStructure(configuration);
        Target target = resolve(configuration, issues);
        if (requestedFileCount(configuration) > catalog.snapshot().submissionPolicy().maximumTasksPerSubmission())
            issue(issues, IssueCode.REQUESTED_FILE_COUNT_UNSUPPORTED, IssueField.REQUESTED_FILE_COUNT, configuration.modelSelection(), "Requested count exceeds platform submission policy");
        if (target != null) {
            Values candidate = values(configuration); Values requirements = intent(configuration).requirements();
            issues.addAll(candidateIssues(candidate, target, facts, true));
            requirementIssues(candidate, requirements, target, facts, issues);
            ProviderBinding binding = frozen == null ? target.binding() : frozen.providerBinding();
            if (!binding.equals(target.binding())) issue(issues, IssueCode.CAPABILITY_REVISION_UNAVAILABLE, IssueField.PROVIDER_BINDING, target.selection(), "Pinned binding is no longer available");
            if (!facts.executionAdapters().contains(binding)) issue(issues, IssueCode.EXECUTION_ADAPTER_UNAVAILABLE, IssueField.PROVIDER_BINDING, target.selection(), "Verified normalized adapter and asset resolution unavailable");
            if (!facts.resultVerifiers().contains(binding)) issue(issues, IssueCode.RESULT_VERIFIER_UNAVAILABLE, IssueField.PROVIDER_BINDING, target.selection(), "Verified single-file result boundary unavailable");
        }
        return new PlanValidation(issues.isEmpty(), issues, catalog.snapshot().catalogRevision());
    }

    private void validateStructure(PlanConfiguration configuration) {
        GenerationPlanCodec codec = new GenerationPlanCodec();
        codec.read(codec.write(configuration), PlanConfiguration.class);
        Values candidate = values(configuration); Values requirements = intent(configuration).requirements();
        uniqueReferences(candidate.references()); if (requirements.references() != null) uniqueReferences(requirements.references());
        EnumMap<ParameterKey, ParameterOrigin> origins = origins(configuration.parameterOrigins());
        Set<ParameterKey> present = populated(candidate);
        ModelCatalog.require(present.equals(origins.keySet()), "Parameter origins must exactly match present values");
        for (ParameterOrigin origin : origins.values()) {
            ModelCatalog.require((origin.source() == ParameterOriginSource.EXPLICIT) == (origin.sourceModel() == null), "Invalid parameter origin source");
            ModelCatalog.require(origin.source() != ParameterOriginSource.MODEL_DEFAULT || (origin.field() != ParameterKey.PROMPT && origin.field() != ParameterKey.REFERENCES), "Prompt and references have no model defaults");
        }
    }
    private static void uniqueReferences(List<ReferenceAsset> references) {
        Set<UUID> ids = new HashSet<>();
        for (ReferenceAsset reference : references) ModelCatalog.require(ids.add(reference.assetId()), "Duplicate reference asset");
    }

    private void rejectUnsupportedAssignments(PlanConfiguration configuration, Set<ParameterKey> assigned, AdmissionFacts facts) {
        // 未选模型时只检查平台合同；未知元数据保留为提交阻碍，不凭空判定显式引用不兼容。
        Values values = values(configuration); uniqueReferences(values.references());
        if (intent(configuration).requirements().references() != null) uniqueReferences(intent(configuration).requirements().references());
        Target target = resolve(configuration, null);
        if (target == null) return;
        List<ValidationIssue> invalid = candidateIssues(values, target, facts, false).stream()
            .filter(i -> assigned.stream().anyMatch(k -> k.name().equals(i.field().name()))
                || (i.code() == IssueCode.REFERENCE_LIMIT_EXCEEDED && assigned.contains(ParameterKey.DURATION_SECONDS))
                || (i.code() == IssueCode.SIZE_DURATION_COMBINATION_UNSUPPORTED && (assigned.contains(ParameterKey.DURATION_SECONDS) || assigned.contains(ParameterKey.ASPECT_RATIO) || assigned.contains(ParameterKey.RESOLUTION_ID))))
            .filter(i -> i.code() != IssueCode.REFERENCE_METADATA_UNAVAILABLE && i.code() != IssueCode.REFERENCE_UNAVAILABLE)
            .filter(i -> !(i.field() == IssueField.REFERENCES && values.references().isEmpty() && !inputDurationExceeded(values.references(), values.duration(), target, facts))).toList();
        if (!invalid.isEmpty()) throw new DomainException(PlanToolErrorCode.INVALID_ARGUMENT, "Explicit candidate is unsupported", invalid);
    }

    private List<ValidationIssue> candidateIssues(Values candidate, Target target, AdmissionFacts facts, boolean complete) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (candidate.prompt() == null) { if (complete) issue(issues, IssueCode.REQUIRED_PARAMETER_MISSING, IssueField.PROMPT, target.selection(), "Prompt required"); }
        else if (!promptSupported(candidate.prompt(), target)) issue(issues, IssueCode.PARAMETER_UNSUPPORTED, IssueField.PROMPT, target.selection(), "Prompt exceeds profile UTF-8 limit");
        if (candidate.size() == null) { if (complete) issue(issues, IssueCode.REQUIRED_PARAMETER_MISSING, IssueField.ASPECT_RATIO, target.selection(), "Size required"); }
        else if (candidate.size() instanceof PixelSize pixels) {
            if (target.video() != null || !ModelCatalog.custom(pixels, target.sizes())) issue(issues, IssueCode.PARAMETER_UNSUPPORTED, IssueField.PIXEL_DIMENSIONS, target.selection(), "Custom pixel size unsupported");
        } else {
            RatioSize ratio = (RatioSize) candidate.size();
            if (ratio.aspectRatio() == null && complete) issue(issues, IssueCode.REQUIRED_PARAMETER_MISSING, IssueField.ASPECT_RATIO, target.selection(), "Aspect ratio required");
            if (ratio.resolutionId() == null && complete) issue(issues, IssueCode.REQUIRED_PARAMETER_MISSING, IssueField.RESOLUTION_ID, target.selection(), "Resolution required");
            if (!ModelCatalog.possible(ratio, target.sizes())) issue(issues, IssueCode.PARAMETER_UNSUPPORTED, ratio.aspectRatio() != null ? IssueField.ASPECT_RATIO : IssueField.RESOLUTION_ID, target.selection(), "Ratio resolution mapping unsupported");
        }
        if (candidate.format() == null) { if (complete) issue(issues, IssueCode.REQUIRED_PARAMETER_MISSING, IssueField.OUTPUT_FORMAT, target.selection(), "Output format required"); }
        else if (!formatSupported(candidate.format(), target)) issue(issues, IssueCode.PARAMETER_UNSUPPORTED, IssueField.OUTPUT_FORMAT, target.selection(), "Output format unsupported");
        if (target.video() != null) {
            if (candidate.duration() == null) { if (complete) issue(issues, IssueCode.REQUIRED_PARAMETER_MISSING, IssueField.DURATION_SECONDS, target.selection(), "Duration required"); }
            else if (!durationSupported(candidate.duration(), candidate.size(), target)) issue(issues, IssueCode.SIZE_DURATION_COMBINATION_UNSUPPORTED, IssueField.DURATION_SECONDS, target.selection(), "Size duration combination unsupported");
            if (candidate.audio() == null) { if (complete) issue(issues, IssueCode.REQUIRED_PARAMETER_MISSING, IssueField.GENERATE_AUDIO, target.selection(), "Audio track choice required"); }
            else if (!audioSupported(candidate.audio(), target)) issue(issues, IssueCode.PARAMETER_UNSUPPORTED, IssueField.GENERATE_AUDIO, target.selection(), "Audio track choice unsupported");
        }
        issues.addAll(referenceIssues(candidate.references(), target, facts, candidate.duration()));
        return issues;
    }

    private void requirementIssues(Values candidate, Values required, Target target, AdmissionFacts facts, List<ValidationIssue> issues) {
        if (required.prompt() != null && (!required.prompt().equals(candidate.prompt()) || !promptSupported(required.prompt(), target))) issue(issues, IssueCode.REQUIREMENT_UNSATISFIED, IssueField.PROMPT, target.selection(), "Exact prompt requirement unresolved");
        if (required.size() instanceof PixelSize pixels && !sizeMatches(pixels, candidate.size(), target)) issue(issues, IssueCode.REQUIREMENT_UNSATISFIED, IssueField.PIXEL_DIMENSIONS, target.selection(), "Pixel requirement unresolved");
        else if (required.size() instanceof RatioSize wanted) {
            RatioSize actual = candidate.size() instanceof RatioSize ratio ? ratio
                : candidate.size() instanceof PixelSize pixels ? new RatioSize("ratio_size", ModelCatalog.ratio(new AspectRatio(pixels.dimensions().width(), pixels.dimensions().height())), null)
                : new RatioSize("ratio_size", null, null);
            if (wanted.aspectRatio() != null && (actual.aspectRatio() == null || !ModelCatalog.ratio(wanted.aspectRatio()).equals(ModelCatalog.ratio(actual.aspectRatio()))
                || (!(candidate.size() instanceof PixelSize) && !ModelCatalog.possible(new RatioSize("ratio_size", wanted.aspectRatio(), null), target.sizes())))) issue(issues, IssueCode.REQUIREMENT_UNSATISFIED, IssueField.ASPECT_RATIO, target.selection(), "Aspect ratio requirement unresolved");
            if (wanted.resolutionId() != null && (!wanted.resolutionId().equals(actual.resolutionId()) || !ModelCatalog.possible(wanted, target.sizes()))) issue(issues, IssueCode.REQUIREMENT_UNSATISFIED, IssueField.RESOLUTION_ID, target.selection(), "Resolution requirement unresolved");
        }
        if (required.references() != null && (!required.references().equals(candidate.references()) || !referenceIssues(required.references(), target, facts, candidate.duration()).isEmpty())) issue(issues, IssueCode.REQUIREMENT_UNSATISFIED, IssueField.REFERENCES, target.selection(), "Exact ordered reference requirement unresolved");
        if (required.format() != null && (!required.format().equals(candidate.format()) || !formatSupported(required.format(), target))) issue(issues, IssueCode.REQUIREMENT_UNSATISFIED, IssueField.OUTPUT_FORMAT, target.selection(), "Output format requirement unresolved");
        if (required.duration() != null && (!required.duration().equals(candidate.duration()) || !durationSupported(required.duration(), candidate.size(), target))) issue(issues, IssueCode.REQUIREMENT_UNSATISFIED, IssueField.DURATION_SECONDS, target.selection(), "Duration requirement unresolved");
        if (required.audio() != null && (!required.audio().equals(candidate.audio()) || !audioSupported(required.audio(), target))) issue(issues, IssueCode.REQUIREMENT_UNSATISFIED, IssueField.GENERATE_AUDIO, target.selection(), "Audio requirement unresolved");
    }

    /** 对每个引用使用可信媒体事实；未知事实保留阻碍，不能视为无限制媒体。 */
    private List<ValidationIssue> referenceIssues(List<ReferenceAsset> references, Target target, AdmissionFacts facts, Integer outputDuration) {
        List<ValidationIssue> issues = new ArrayList<>(); ReferenceRules rules = target.references();
        if (references.size() < rules.minimumTotalCount() || references.size() > rules.maximumTotalCount()) issue(issues, IssueCode.REFERENCE_LIMIT_EXCEEDED, IssueField.REFERENCES, target.selection(), "Total reference count outside profile limits");
        EnumMap<ReferenceMediaType, Integer> counts = new EnumMap<>(ReferenceMediaType.class);
        long videoDuration = 0; long audioDuration = 0; boolean unknown = false;
        for (int index = 0; index < references.size(); index++) {
            ReferenceAsset reference = references.get(index);
            if (facts.unavailableAssets().contains(reference.assetId())) {
                referenceIssue(issues, IssueCode.REFERENCE_UNAVAILABLE, index, target.selection(), "Reference is unavailable"); unknown = true; continue;
            }
            VerifiedMediaMetadata metadata = facts.metadata().get(reference.assetId());
            if (metadata == null) {
                referenceIssue(issues, IssueCode.REFERENCE_METADATA_UNAVAILABLE, index, target.selection(), "Trusted immutable metadata required"); unknown = true; continue;
            }
            ReferenceMediaType media = metadata instanceof VerifiedImageMetadata ? ReferenceMediaType.IMAGE : metadata instanceof VerifiedVideoMetadata ? ReferenceMediaType.VIDEO : ReferenceMediaType.AUDIO;
            counts.merge(media, 1, Integer::sum);
            ReferenceMediaLimit limit = rules.mediaLimits().stream().filter(l -> l.mediaType() == media).findFirst().orElse(null);
            if (limit == null) { referenceIssue(issues, IssueCode.REFERENCE_TYPE_MISMATCH, index, target.selection(), "Reference media unsupported"); continue; }
            long duration = metadata instanceof VerifiedVideoMetadata video ? video.durationMilliseconds() : metadata instanceof VerifiedAudioMetadata audio ? audio.durationMilliseconds() : 0;
            PixelDimensions dimensions = metadata instanceof VerifiedImageMetadata image ? image.dimensions() : metadata instanceof VerifiedVideoMetadata video ? video.dimensions() : null;
            if (!limit.mimeTypes().contains(metadata.detectedMimeType())) referenceIssue(issues, IssueCode.REFERENCE_TYPE_MISMATCH, index, target.selection(), "Detected MIME unsupported");
            if (metadata.sizeBytes() > limit.maxAssetBytes() || (limit.maxDurationMilliseconds() != null && duration > limit.maxDurationMilliseconds()) || (limit.dimensions() != null && !within(dimensions, limit.dimensions()))) referenceIssue(issues, IssueCode.REFERENCE_LIMIT_EXCEEDED, index, target.selection(), "Reference byte size dimensions or duration exceeds limits");
            // 整数交叉乘法保留闭区间端点；此处失败会阻止冻结及后续 Task/Outbox 创建。
            if (limit.aspectRatioRange() != null && !withinRatio(dimensions, limit.aspectRatioRange()))
                referenceIssue(issues, IssueCode.REFERENCE_LIMIT_EXCEEDED, index, target.selection(), "Reference aspect ratio outside profile limits");
            try {
                if (media == ReferenceMediaType.VIDEO) videoDuration = Math.addExact(videoDuration, duration);
                if (media == ReferenceMediaType.AUDIO) audioDuration = Math.addExact(audioDuration, duration);
            } catch (ArithmeticException exception) { referenceIssue(issues, IssueCode.REFERENCE_LIMIT_EXCEEDED, index, target.selection(), "Aggregate reference duration overflow"); }
        }
        for (ReferenceMediaLimit limit : rules.mediaLimits()) {
            int count = counts.getOrDefault(limit.mediaType(), 0);
            if ((!unknown && count < limit.minimumCount()) || count > limit.maximumCount()) issue(issues, IssueCode.REFERENCE_COMBINATION_UNSUPPORTED, IssueField.REFERENCES, target.selection(), "Reference media combination violates profile");
        }
        if ((rules.maxTotalVideoDurationMilliseconds() != null && videoDuration > rules.maxTotalVideoDurationMilliseconds())
            || (rules.maxTotalAudioDurationMilliseconds() != null && audioDuration > rules.maxTotalAudioDurationMilliseconds())) issue(issues, IssueCode.REFERENCE_LIMIT_EXCEEDED, IssueField.REFERENCES, target.selection(), "Aggregate reference duration exceeds profile");
        if (inputDurationExceeded(references, outputDuration, target, facts)) issue(issues, IssueCode.REFERENCE_LIMIT_EXCEEDED, IssueField.REFERENCES, target.selection(), "Input video plus output duration exceeds profile");
        return issues;
    }
    /** 元数据缺少图像尺寸时不能证明符合比例限制。 */
    private static boolean withinRatio(PixelDimensions dimensions, AspectRatioRange range) {
        return dimensions != null
            && (long) dimensions.width() * range.minimum().heightUnits() >= (long) dimensions.height() * range.minimum().widthUnits()
            && (long) dimensions.width() * range.maximum().heightUnits() <= (long) dimensions.height() * range.maximum().widthUnits();
    }
    private boolean inputDurationExceeded(List<ReferenceAsset> references, Integer duration, Target target, AdmissionFacts facts) {
        Long maximum = target.references().maxInputVideoPlusOutputDurationMilliseconds();
        if (maximum == null || duration == null) return false;
        try {
            long total = Math.multiplyExact(duration.longValue(), 1000L);
            for (ReferenceAsset reference : references) {
                VerifiedMediaMetadata metadata = facts.metadata().get(reference.assetId());
                if (metadata instanceof VerifiedVideoMetadata video) total = Math.addExact(total, video.durationMilliseconds());
            }
            return total > maximum;
        } catch (ArithmeticException exception) { return true; }
    }
    private static boolean within(PixelDimensions dimensions, PixelBounds bounds) {
        return dimensions != null && dimensions.width() >= bounds.minWidth() && dimensions.width() <= bounds.maxWidth()
            && dimensions.height() >= bounds.minHeight() && dimensions.height() <= bounds.maxHeight() && (long) dimensions.width() * dimensions.height() <= bounds.maxPixels();
    }
    private static boolean promptSupported(String prompt, Target target) {
        return !prompt.isBlank() && prompt.codePointCount(0, prompt.length()) <= 64000 && prompt.getBytes(StandardCharsets.UTF_8).length <= target.prompts().maxUtf8Bytes();
    }
    private static boolean formatSupported(Encoding format, Target target) {
        return target.image() != null ? target.image().outputFormats().stream().anyMatch(f -> f.name().equals(format.name())) : target.video().outputFormats().stream().anyMatch(f -> f.name().equals(format.name()));
    }
    private static boolean audioSupported(boolean audio, Target target) { return target.video() != null && target.video().generateAudioValues().contains(audio); }
    private static boolean durationSupported(int duration, DraftOutputSize size, Target target) {
        if (target.video() == null) return false;
        return target.video().outputOptions().stream().anyMatch(o -> ModelCatalog.contains(o.durationSeconds(), duration)
            && target.sizes().presets().stream().anyMatch(p -> p.presetId().equals(o.sizePresetId()) && (size == null || size instanceof RatioSize ratio
                && (ratio.aspectRatio() == null || ModelCatalog.ratio(ratio.aspectRatio()).equals(ModelCatalog.ratio(p.aspectRatio())))
                && (ratio.resolutionId() == null || ratio.resolutionId().equals(p.resolutionId())))));
    }
    private ModelSelection choose(MediaType media, ModelChoice choice) {
        if (choice == null) return null;
        ModelSelection selection = new ModelSelection(choice.modelId(), choice.profileId(), choice.expectedCapabilityRevision());
        List<ValidationIssue> issues = new ArrayList<>(); resolve(media, selection, issues);
        if (!issues.isEmpty()) throw new DomainException(issues.get(0).code() == IssueCode.CAPABILITY_REVISION_UNAVAILABLE ? PlanToolErrorCode.CAPABILITIES_CHANGED : PlanToolErrorCode.TARGET_NOT_AVAILABLE, "Target is not available", issues);
        return selection;
    }
    private Target resolve(PlanConfiguration configuration, List<ValidationIssue> issues) { return resolve(media(configuration), configuration.modelSelection(), issues); }
    private Target resolve(MediaType media, ModelSelection selection, List<ValidationIssue> issues) {
        List<ValidationIssue> output = issues == null ? new ArrayList<>() : issues;
        if (selection == null) { issue(output, IssueCode.MODEL_REQUIRED, IssueField.MODEL, null, "Model selection required"); return null; }
        ModelDefinition definition = catalog.find(media, selection);
        if (definition == null) { issue(output, IssueCode.MODEL_NOT_FOUND, IssueField.MODEL, selection, "Model not found for media type"); return null; }
        if (!definition.enabled()) { issue(output, IssueCode.MODEL_DISABLED, IssueField.MODEL, selection, "Model disabled"); return null; }
        if (!definition.capabilityRevision().equals(selection.capabilityRevision())) { issue(output, IssueCode.CAPABILITY_REVISION_UNAVAILABLE, IssueField.MODEL, selection, "Pinned capability revision unavailable"); return null; }
        if (definition instanceof ImageModelDefinition image) {
            ImageProfile profile = image.profiles().stream().filter(p -> p.profileId().equals(selection.profileId())).findFirst().orElse(null);
            if (profile instanceof VerifiedImageProfile verified) return new Target(selection, definition.providerBinding(), verified, null);
            issue(output, profile instanceof UnavailableImageProfile unavailable && unavailable.status() == UnavailableImageProfileStatus.UNSUPPORTED ? IssueCode.PROFILE_UNSUPPORTED : IssueCode.PROFILE_UNKNOWN, IssueField.PROFILE, selection, "Image profile unavailable");
        } else {
            VideoModelDefinition video = (VideoModelDefinition) definition;
            VideoProfile profile = video.profiles().stream().filter(p -> p.profileId().equals(selection.profileId())).findFirst().orElse(null);
            if (profile instanceof VerifiedVideoProfile verified) return new Target(selection, definition.providerBinding(), null, verified);
            issue(output, profile instanceof UnavailableVideoProfile unavailable && unavailable.status() == UnavailableVideoProfileStatus.UNSUPPORTED ? IssueCode.PROFILE_UNSUPPORTED : IssueCode.PROFILE_UNKNOWN, IssueField.PROFILE, selection, "Video profile unavailable");
        }
        return null;
    }

    /** 编译只产生一份文件的请求；提交批次数留在意图中，不能进入提供方参数。 */
    private NormalizedGenerationRequest compile(PlanConfiguration configuration) {
        Target target = resolve(configuration, null); Values values = values(configuration);
        SizePreset preset = ModelCatalog.preset(values.size(), target.sizes());
        if (configuration instanceof ImagePlanConfiguration) {
            ExecutableSize size = values.size() instanceof PixelSize pixels ? pixels : new ResolvedPresetSize("resolved_preset_size", canonicalPreset(preset));
            ImageOutputFormat format = ImageOutputFormat.valueOf(values.format().name());
            return new ImageGenerationRequest("image_generation_plan_v1", 1, target.selection(), target.binding(),
                new ImageExecutionParameters("image_execution_parameters", values.prompt(), size, values.references(), format, ImageMode.IMAGE),
                new ImageResultExpectations("image_result_expectations", 1, size, format));
        }
        ResolvedPresetSize size = new ResolvedPresetSize("resolved_preset_size", canonicalPreset(preset));
        VideoOutputFormat format = VideoOutputFormat.valueOf(values.format().name());
        return new VideoGenerationRequest("video_generation_plan_v1", 1, target.selection(), target.binding(),
            new VideoExecutionParameters("video_execution_parameters", values.prompt(), size, values.references(), format, values.duration(), values.audio(), VideoMode.VIDEO),
            new VideoResultExpectations("video_result_expectations", 1, size, format, values.duration(), target.video().durationToleranceMilliseconds(), values.audio()));
    }
    private static boolean defaulted(EnumMap<ParameterKey, ParameterOrigin> origins, ParameterKey key) {
        return origins.containsKey(key) && origins.get(key).source() == ParameterOriginSource.MODEL_DEFAULT;
    }
    private static boolean sizeMatches(DraftOutputSize wanted, DraftOutputSize actual, Target target) {
        if (wanted instanceof RatioSize required) {
            RatioSize candidate = actual instanceof RatioSize ratio ? ratio
                : actual instanceof PixelSize pixels ? new RatioSize("ratio_size", ModelCatalog.ratio(new AspectRatio(pixels.dimensions().width(), pixels.dimensions().height())), null)
                : new RatioSize("ratio_size", null, null);
            return (required.aspectRatio() == null || (candidate.aspectRatio() != null && ModelCatalog.ratio(required.aspectRatio()).equals(ModelCatalog.ratio(candidate.aspectRatio()))))
                && (required.resolutionId() == null || required.resolutionId().equals(candidate.resolutionId()));
        }
        if (!(wanted instanceof PixelSize pixels)) return Objects.equals(wanted, actual);
        if (actual instanceof PixelSize candidate) return pixels.dimensions().equals(candidate.dimensions()) && (target == null || ModelCatalog.custom(candidate, target.sizes()));
        SizePreset preset = target == null ? null : ModelCatalog.preset(actual, target.sizes());
        return preset != null && pixels.dimensions().equals(preset.pixelDimensions());
    }
    private static SizePreset canonicalPreset(SizePreset preset) { return new SizePreset(preset.presetId(), ModelCatalog.ratio(preset.aspectRatio()), preset.resolutionId(), preset.pixelDimensions()); }
    private static void issue(List<ValidationIssue> issues, IssueCode code, IssueField field, ModelSelection selection, String message) { issues.add(new ValidationIssue(code, field, null, message, selection)); }
    private static void referenceIssue(List<ValidationIssue> issues, IssueCode code, int index, ModelSelection selection, String message) { issues.add(new ValidationIssue(code, IssueField.REFERENCES, index, message, selection)); }

    private static MediaType media(PlanConfiguration configuration) { return configuration instanceof ImagePlanConfiguration ? MediaType.IMAGE : MediaType.VIDEO; }
    private static Encoding encoding(ImageOutputFormat value) { return value == null ? null : Encoding.valueOf(value.name()); }
    private static Encoding encoding(VideoOutputFormat value) { return value == null ? null : Encoding.valueOf(value.name()); }
    private static Values values(PlanConfiguration configuration) {
        if (configuration instanceof ImagePlanConfiguration image) {
            ImageParameters p = image.parameters(); return new Values(p.prompt(), p.size(), p.references(), encoding(p.outputFormat()), null, null);
        }
        VideoParameters p = ((VideoPlanConfiguration) configuration).parameters();
        return new Values(p.prompt(), p.size(), p.references(), encoding(p.outputFormat()), p.durationSeconds(), p.generateAudio());
    }
    private static IntentValues intent(PlanConfiguration configuration) {
        if (configuration instanceof ImagePlanConfiguration image) {
            ImageRequirements r = image.intent().requirements();
            return new IntentValues(image.intent().description(), new Values(r.prompt(), r.size(), r.references(), encoding(r.outputFormat()), null, null), r.requestedFileCount(), r.generationMode() == null ? null : MediaType.IMAGE);
        }
        VideoPlanConfiguration video = (VideoPlanConfiguration) configuration; VideoRequirements r = video.intent().requirements();
        return new IntentValues(video.intent().description(), new Values(r.prompt(), r.size(), r.references(), encoding(r.outputFormat()), r.durationSeconds(), r.generateAudio()), r.requestedFileCount(), r.generationMode() == null ? null : MediaType.VIDEO);
    }
    private static Values normalized(Values source) {
        DraftOutputSize size = source.size();
        if (size instanceof RatioSize ratio) size = new RatioSize("ratio_size", ratio.aspectRatio() == null ? null : ModelCatalog.ratio(ratio.aspectRatio()), ratio.resolutionId());
        return new Values(source.prompt(), size, source.references(), source.format(), source.duration(), source.audio());
    }
    private static PlanConfiguration rebuild(PlanConfiguration template, IntentValues intent, Values parameters, List<ParameterOrigin> origins) {
        Values requirements = intent.requirements();
        if (template instanceof ImagePlanConfiguration) return new ImagePlanConfiguration("image_plan_configuration", template.modelSelection(),
            new ImageIntent("image_intent", intent.description(), new ImageRequirements("image_requirements", requirements.prompt(), requirements.size(), intent.fileCount(), requirements.references(), requirements.format() == null ? null : ImageOutputFormat.valueOf(requirements.format().name()), intent.mode() == null ? null : ImageMode.IMAGE)),
            new ImageParameters("image_parameters", parameters.prompt(), parameters.size(), parameters.references(), parameters.format() == null ? null : ImageOutputFormat.valueOf(parameters.format().name())), origins);
        return new VideoPlanConfiguration("video_plan_configuration", template.modelSelection(),
            new VideoIntent("video_intent", intent.description(), new VideoRequirements("video_requirements", requirements.prompt(), requirements.size(), intent.fileCount(), requirements.references(), requirements.format() == null ? null : VideoOutputFormat.valueOf(requirements.format().name()), requirements.duration(), requirements.audio(), intent.mode() == null ? null : VideoMode.VIDEO)),
            new VideoParameters("video_parameters", parameters.prompt(), parameters.size(), parameters.references(), parameters.format() == null ? null : VideoOutputFormat.valueOf(parameters.format().name()), parameters.duration(), parameters.audio()), origins);
    }
    private static PlanConfiguration select(PlanConfiguration source, ModelSelection selection) {
        if (source instanceof ImagePlanConfiguration image) return new ImagePlanConfiguration(image.type(), selection, image.intent(), image.parameters(), image.parameterOrigins());
        VideoPlanConfiguration video = (VideoPlanConfiguration) source; return new VideoPlanConfiguration(video.type(), selection, video.intent(), video.parameters(), video.parameterOrigins());
    }
    private static Set<ParameterKey> populated(Values values) {
        Set<ParameterKey> fields = java.util.EnumSet.noneOf(ParameterKey.class);
        if (values.prompt() != null) fields.add(ParameterKey.PROMPT);
        if (values.size() instanceof PixelSize) fields.add(ParameterKey.PIXEL_DIMENSIONS);
        else if (values.size() instanceof RatioSize ratio) {
            if (ratio.aspectRatio() != null) fields.add(ParameterKey.ASPECT_RATIO);
            if (ratio.resolutionId() != null) fields.add(ParameterKey.RESOLUTION_ID);
        }
        if (values.references() != null && !values.references().isEmpty()) fields.add(ParameterKey.REFERENCES);
        if (values.format() != null) fields.add(ParameterKey.OUTPUT_FORMAT);
        if (values.duration() != null) fields.add(ParameterKey.DURATION_SECONDS);
        if (values.audio() != null) fields.add(ParameterKey.GENERATE_AUDIO);
        return fields;
    }
    private static List<ParameterOrigin> explicitOrigins(Values values) { return populated(values).stream().map(k -> new ParameterOrigin(k, ParameterOriginSource.EXPLICIT, null)).toList(); }
    private static EnumMap<ParameterKey, ParameterOrigin> origins(List<ParameterOrigin> origins) {
        EnumMap<ParameterKey, ParameterOrigin> map = new EnumMap<>(ParameterKey.class);
        for (ParameterOrigin origin : origins) ModelCatalog.require(map.put(origin.field(), origin) == null, "Duplicate parameter origin");
        return map;
    }
    private static List<ParameterOrigin> retainOrigins(List<ParameterOrigin> originals, Values values) {
        EnumMap<ParameterKey, ParameterOrigin> map = origins(originals);
        map.keySet().retainAll(populated(values)); return new ArrayList<>(map.values());
    }
    private static void origin(EnumMap<ParameterKey, ParameterOrigin> origins, ParameterKey key, ModelSelection model, List<AppliedDefault> defaults) {
        origins.put(key, new ParameterOrigin(key, model == null ? ParameterOriginSource.EXPLICIT : ParameterOriginSource.MODEL_DEFAULT, model));
        if (model != null) defaults.add(new AppliedDefault(key, model));
    }
    private static List<IssueField> nonMigrated(IntentValues intent) {
        Values r = intent.requirements(); List<IssueField> fields = new ArrayList<>();
        if (r.prompt() != null) fields.add(IssueField.PROMPT);
        if (r.size() instanceof PixelSize) fields.add(IssueField.PIXEL_DIMENSIONS);
        if (r.size() instanceof RatioSize ratio && ratio.resolutionId() != null) fields.add(IssueField.RESOLUTION_ID);
        if (r.references() != null) fields.add(IssueField.REFERENCES);
        if (r.format() != null) fields.add(IssueField.OUTPUT_FORMAT);
        if (r.duration() != null) fields.add(IssueField.DURATION_SECONDS);
        if (r.audio() != null) fields.add(IssueField.GENERATE_AUDIO);
        if (intent.mode() != null) fields.add(IssueField.GENERATION_MODE);
        return fields;
    }

    /** SET/CLEAR 替换整个值，只有执行候选修改其来源；要求修改不等同于候选修改。 */
    private static PlanConfiguration patch(PlanConfiguration source, PlanPatch patch) {
        Values p = values(source); IntentValues original = intent(source); Values r = original.requirements();
        EnumMap<ParameterKey, ParameterOrigin> origins = origins(source.parameterOrigins());
        if (patch instanceof ImagePlanPatch typed && source instanceof ImagePlanConfiguration) {
            ImageParametersPatch changes = typed.parameters(); ImageRequirementsPatch requirements = typed.requirements();
            String prompt = changes.prompt() instanceof SetPrompt set ? set.value() : changes.prompt() instanceof ClearChange ? null : p.prompt();
            if (!(changes.prompt() instanceof KeepChange)) { origins.remove(ParameterKey.PROMPT); if (prompt != null) origins.put(ParameterKey.PROMPT, new ParameterOrigin(ParameterKey.PROMPT, ParameterOriginSource.EXPLICIT, null)); }
            String requiredPrompt = requirements.prompt() instanceof SetPrompt set ? set.value() : requirements.prompt() instanceof ClearChange ? null : r.prompt();
            DraftOutputSize size = changes.size() instanceof SetSize set ? set.value() : changes.size() instanceof ClearChange ? null : p.size();
            if (!(changes.size() instanceof KeepChange)) {
                origins.remove(ParameterKey.ASPECT_RATIO); origins.remove(ParameterKey.RESOLUTION_ID); origins.remove(ParameterKey.PIXEL_DIMENSIONS);
                for (ParameterOrigin origin : explicitOrigins(new Values(null, size, List.of(), null, null, null))) origins.put(origin.field(), origin);
            }
            DraftOutputSize requiredSize = requirements.size() instanceof SetSize set ? set.value() : requirements.size() instanceof ClearChange ? null : r.size();
            List<ReferenceAsset> references = changes.references() instanceof SetReferences set ? set.value() : changes.references() instanceof ClearChange ? List.of() : p.references();
            if (!(changes.references() instanceof KeepChange)) { origins.remove(ParameterKey.REFERENCES); if (references != null && !references.isEmpty()) origins.put(ParameterKey.REFERENCES, new ParameterOrigin(ParameterKey.REFERENCES, ParameterOriginSource.EXPLICIT, null)); }
            List<ReferenceAsset> requiredReferences = requirements.references() instanceof SetReferences set ? set.value() : requirements.references() instanceof ClearChange ? null : r.references();
            Encoding format = changes.outputFormat() instanceof SetImageOutputFormat set ? encoding(set.value()) : changes.outputFormat() instanceof ClearChange ? null : p.format();
            if (!(changes.outputFormat() instanceof KeepChange)) { origins.remove(ParameterKey.OUTPUT_FORMAT); if (format != null) origins.put(ParameterKey.OUTPUT_FORMAT, new ParameterOrigin(ParameterKey.OUTPUT_FORMAT, ParameterOriginSource.EXPLICIT, null)); }
            Encoding requiredFormat = requirements.outputFormat() instanceof SetImageOutputFormat set ? encoding(set.value()) : requirements.outputFormat() instanceof ClearChange ? null : r.format();
            String description = typed.description() instanceof SetDescription set ? set.value() : original.description();
            Integer count = requirements.requestedFileCount() instanceof SetRequestedFileCount set ? set.value() : requirements.requestedFileCount() instanceof ClearChange ? null : original.fileCount();
            MediaType mode = requirements.generationMode() instanceof SetImageMode ? MediaType.IMAGE : requirements.generationMode() instanceof ClearChange ? null : original.mode();
            return rebuild(source, new IntentValues(description, normalized(new Values(requiredPrompt, requiredSize, requiredReferences, requiredFormat, null, null)), count, mode), normalized(new Values(prompt, size, references, format, null, null)), new ArrayList<>(origins.values()));
        }
        else if (patch instanceof VideoPlanPatch typed && source instanceof VideoPlanConfiguration) {
            VideoParametersPatch changes = typed.parameters(); VideoRequirementsPatch requirements = typed.requirements();
            String prompt = changes.prompt() instanceof SetPrompt set ? set.value() : changes.prompt() instanceof ClearChange ? null : p.prompt();
            if (!(changes.prompt() instanceof KeepChange)) { origins.remove(ParameterKey.PROMPT); if (prompt != null) origins.put(ParameterKey.PROMPT, new ParameterOrigin(ParameterKey.PROMPT, ParameterOriginSource.EXPLICIT, null)); }
            String requiredPrompt = requirements.prompt() instanceof SetPrompt set ? set.value() : requirements.prompt() instanceof ClearChange ? null : r.prompt();
            DraftOutputSize size = changes.size() instanceof SetSize set ? set.value() : changes.size() instanceof ClearChange ? null : p.size();
            if (!(changes.size() instanceof KeepChange)) {
                origins.remove(ParameterKey.ASPECT_RATIO); origins.remove(ParameterKey.RESOLUTION_ID); origins.remove(ParameterKey.PIXEL_DIMENSIONS);
                for (ParameterOrigin origin : explicitOrigins(new Values(null, size, List.of(), null, null, null))) origins.put(origin.field(), origin);
            }
            DraftOutputSize requiredSize = requirements.size() instanceof SetSize set ? set.value() : requirements.size() instanceof ClearChange ? null : r.size();
            List<ReferenceAsset> references = changes.references() instanceof SetReferences set ? set.value() : changes.references() instanceof ClearChange ? List.of() : p.references();
            if (!(changes.references() instanceof KeepChange)) { origins.remove(ParameterKey.REFERENCES); if (references != null && !references.isEmpty()) origins.put(ParameterKey.REFERENCES, new ParameterOrigin(ParameterKey.REFERENCES, ParameterOriginSource.EXPLICIT, null)); }
            List<ReferenceAsset> requiredReferences = requirements.references() instanceof SetReferences set ? set.value() : requirements.references() instanceof ClearChange ? null : r.references();
            Encoding format = changes.outputFormat() instanceof SetVideoOutputFormat set ? encoding(set.value()) : changes.outputFormat() instanceof ClearChange ? null : p.format();
            if (!(changes.outputFormat() instanceof KeepChange)) { origins.remove(ParameterKey.OUTPUT_FORMAT); if (format != null) origins.put(ParameterKey.OUTPUT_FORMAT, new ParameterOrigin(ParameterKey.OUTPUT_FORMAT, ParameterOriginSource.EXPLICIT, null)); }
            Encoding requiredFormat = requirements.outputFormat() instanceof SetVideoOutputFormat set ? encoding(set.value()) : requirements.outputFormat() instanceof ClearChange ? null : r.format();
            Integer duration = changes.durationSeconds() instanceof SetDuration set ? set.value() : changes.durationSeconds() instanceof ClearChange ? null : p.duration();
            if (!(changes.durationSeconds() instanceof KeepChange)) { origins.remove(ParameterKey.DURATION_SECONDS); if (duration != null) origins.put(ParameterKey.DURATION_SECONDS, new ParameterOrigin(ParameterKey.DURATION_SECONDS, ParameterOriginSource.EXPLICIT, null)); }
            Integer requiredDuration = requirements.durationSeconds() instanceof SetDuration set ? set.value() : requirements.durationSeconds() instanceof ClearChange ? null : r.duration();
            Boolean audio = changes.generateAudio() instanceof SetBoolean set ? set.value() : changes.generateAudio() instanceof ClearChange ? null : p.audio();
            if (!(changes.generateAudio() instanceof KeepChange)) { origins.remove(ParameterKey.GENERATE_AUDIO); if (audio != null) origins.put(ParameterKey.GENERATE_AUDIO, new ParameterOrigin(ParameterKey.GENERATE_AUDIO, ParameterOriginSource.EXPLICIT, null)); }
            Boolean requiredAudio = requirements.generateAudio() instanceof SetBoolean set ? set.value() : requirements.generateAudio() instanceof ClearChange ? null : r.audio();
            String description = typed.description() instanceof SetDescription set ? set.value() : original.description();
            Integer count = requirements.requestedFileCount() instanceof SetRequestedFileCount set ? set.value() : requirements.requestedFileCount() instanceof ClearChange ? null : original.fileCount();
            MediaType mode = requirements.generationMode() instanceof SetVideoMode ? MediaType.VIDEO : requirements.generationMode() instanceof ClearChange ? null : original.mode();
            return rebuild(source, new IntentValues(description, normalized(new Values(requiredPrompt, requiredSize, requiredReferences, requiredFormat, requiredDuration, requiredAudio)), count, mode), normalized(new Values(prompt, size, references, format, duration, audio)), new ArrayList<>(origins.values()));
        }
        throw new DomainException(PlanToolErrorCode.INVALID_ARGUMENT, "Patch media does not match plan", List.of());
    }
    private static Set<ParameterKey> assigned(PlanPatch patch) {
        Set<ParameterKey> fields = java.util.EnumSet.noneOf(ParameterKey.class);
        if (patch instanceof ImagePlanPatch typed) {
            ImageParametersPatch changes = typed.parameters();
            if (!(changes.prompt() instanceof KeepChange)) fields.add(ParameterKey.PROMPT);
            if (!(changes.references() instanceof KeepChange)) fields.add(ParameterKey.REFERENCES);
            if (!(changes.outputFormat() instanceof KeepChange)) fields.add(ParameterKey.OUTPUT_FORMAT);
            if (changes.size() instanceof SetSize set) {
                if (set.value() instanceof PixelSize) fields.add(ParameterKey.PIXEL_DIMENSIONS);
                else { fields.add(ParameterKey.ASPECT_RATIO); fields.add(ParameterKey.RESOLUTION_ID); }
            }
        }
        if (patch instanceof VideoPlanPatch typed) {
            VideoParametersPatch changes = typed.parameters();
            if (!(changes.prompt() instanceof KeepChange)) fields.add(ParameterKey.PROMPT);
            if (!(changes.references() instanceof KeepChange)) fields.add(ParameterKey.REFERENCES);
            if (!(changes.outputFormat() instanceof KeepChange)) fields.add(ParameterKey.OUTPUT_FORMAT);
            if (!(changes.durationSeconds() instanceof KeepChange)) fields.add(ParameterKey.DURATION_SECONDS);
            if (!(changes.generateAudio() instanceof KeepChange)) fields.add(ParameterKey.GENERATE_AUDIO);
            if (changes.size() instanceof SetSize set) {
                if (set.value() instanceof PixelSize) fields.add(ParameterKey.PIXEL_DIMENSIONS);
                else { fields.add(ParameterKey.ASPECT_RATIO); fields.add(ParameterKey.RESOLUTION_ID); }
            }
        }
        return fields;
    }
}
