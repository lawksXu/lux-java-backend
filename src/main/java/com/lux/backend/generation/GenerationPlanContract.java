package com.lux.backend.generation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 生成计划的显式线协议类型；封闭联合保留所有判别字段及可空语义。 */
public final class GenerationPlanContract {
    private GenerationPlanContract() {}
    /** MediaType 的固定协议值。 */
    public enum MediaType { IMAGE, VIDEO }
    /** PlanState 的固定协议值。 */
    public enum PlanState { DRAFT, FROZEN }
    /** ImageMode 的固定协议值。 */
    public enum ImageMode { IMAGE }
    /** VideoMode 的固定协议值。 */
    public enum VideoMode { VIDEO }
    /** ImageOutputFormat 的固定协议值。 */
    public enum ImageOutputFormat { PNG, JPEG }
    /** VideoOutputFormat 的固定协议值。 */
    public enum VideoOutputFormat { MP4, MOV }
    /** ParameterKey 的固定协议值。 */
    public enum ParameterKey { PROMPT, ASPECT_RATIO, RESOLUTION_ID, PIXEL_DIMENSIONS, REFERENCES, OUTPUT_FORMAT, DURATION_SECONDS, GENERATE_AUDIO }
    /** AspectRatio 的已知字段；数组在构造时复制以固定快照。 */
    public record AspectRatio(
        @JsonProperty("width_units") Integer widthUnits,
        @JsonProperty("height_units") Integer heightUnits
    ) {
        public AspectRatio {
            java.util.Objects.requireNonNull(widthUnits, "width_units");
            java.util.Objects.requireNonNull(heightUnits, "height_units");
        }
    }
    /** PixelDimensions 的已知字段；数组在构造时复制以固定快照。 */
    public record PixelDimensions(
        @JsonProperty("width") Integer width,
        @JsonProperty("height") Integer height
    ) {
        public PixelDimensions {
            java.util.Objects.requireNonNull(width, "width");
            java.util.Objects.requireNonNull(height, "height");
        }
    }
    /** RatioSize 的已知字段；数组在构造时复制以固定快照。 */
    public record RatioSize(
        @JsonProperty("type") String type,
        @JsonProperty("aspect_ratio") AspectRatio aspectRatio,
        @JsonProperty("resolution_id") String resolutionId
    ) implements DraftOutputSize {
        public RatioSize {
            java.util.Objects.requireNonNull(type, "type");
            if (!"ratio_size".equals(type)) throw new IllegalArgumentException("Invalid type");
        }
    }
    /** PixelSize 的已知字段；数组在构造时复制以固定快照。 */
    public record PixelSize(
        @JsonProperty("type") String type,
        @JsonProperty("dimensions") PixelDimensions dimensions
    ) implements DraftOutputSize, ExecutableSize {
        public PixelSize {
            java.util.Objects.requireNonNull(type, "type");
            if (!"pixel_size".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(dimensions, "dimensions");
        }
    }
    /** DraftOutputSize 的封闭变体，禁止未知业务结构。 */
    public sealed interface DraftOutputSize permits RatioSize, PixelSize {
        String type();
    }
    /** ImageParameters 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageParameters(
        @JsonProperty("type") String type,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("size") DraftOutputSize size,
        @JsonProperty("references") List<ReferenceAsset> references,
        @JsonProperty("output_format") ImageOutputFormat outputFormat
    ) {
        public ImageParameters {
            java.util.Objects.requireNonNull(type, "type");
            if (!"image_parameters".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(references, "references");
            references = references == null ? null : List.copyOf(references);
        }
    }
    /** VideoParameters 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoParameters(
        @JsonProperty("type") String type,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("size") DraftOutputSize size,
        @JsonProperty("references") List<ReferenceAsset> references,
        @JsonProperty("output_format") VideoOutputFormat outputFormat,
        @JsonProperty("duration_seconds") Integer durationSeconds,
        @JsonProperty("generate_audio") Boolean generateAudio
    ) {
        public VideoParameters {
            java.util.Objects.requireNonNull(type, "type");
            if (!"video_parameters".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(references, "references");
            references = references == null ? null : List.copyOf(references);
        }
    }
    /** ImageRequirements 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageRequirements(
        @JsonProperty("type") String type,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("size") DraftOutputSize size,
        @JsonProperty("requested_file_count") Integer requestedFileCount,
        @JsonProperty("references") List<ReferenceAsset> references,
        @JsonProperty("output_format") ImageOutputFormat outputFormat,
        @JsonProperty("generation_mode") ImageMode generationMode
    ) {
        public ImageRequirements {
            java.util.Objects.requireNonNull(type, "type");
            if (!"image_requirements".equals(type)) throw new IllegalArgumentException("Invalid type");
            references = references == null ? null : List.copyOf(references);
        }
    }
    /** VideoRequirements 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoRequirements(
        @JsonProperty("type") String type,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("size") DraftOutputSize size,
        @JsonProperty("requested_file_count") Integer requestedFileCount,
        @JsonProperty("references") List<ReferenceAsset> references,
        @JsonProperty("output_format") VideoOutputFormat outputFormat,
        @JsonProperty("duration_seconds") Integer durationSeconds,
        @JsonProperty("generate_audio") Boolean generateAudio,
        @JsonProperty("generation_mode") VideoMode generationMode
    ) {
        public VideoRequirements {
            java.util.Objects.requireNonNull(type, "type");
            if (!"video_requirements".equals(type)) throw new IllegalArgumentException("Invalid type");
            references = references == null ? null : List.copyOf(references);
        }
    }
    /** ImageIntent 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageIntent(
        @JsonProperty("type") String type,
        @JsonProperty("description") String description,
        @JsonProperty("requirements") ImageRequirements requirements
    ) {
        public ImageIntent {
            java.util.Objects.requireNonNull(type, "type");
            if (!"image_intent".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(description, "description");
            java.util.Objects.requireNonNull(requirements, "requirements");
        }
    }
    /** VideoIntent 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoIntent(
        @JsonProperty("type") String type,
        @JsonProperty("description") String description,
        @JsonProperty("requirements") VideoRequirements requirements
    ) {
        public VideoIntent {
            java.util.Objects.requireNonNull(type, "type");
            if (!"video_intent".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(description, "description");
            java.util.Objects.requireNonNull(requirements, "requirements");
        }
    }
    /** ModelChoice 的已知字段；数组在构造时复制以固定快照。 */
    public record ModelChoice(
        @JsonProperty("model_id") String modelId,
        @JsonProperty("profile_id") String profileId,
        @JsonProperty("expected_capability_revision") Long expectedCapabilityRevision
    ) {
        public ModelChoice {
            java.util.Objects.requireNonNull(modelId, "model_id");
            java.util.Objects.requireNonNull(profileId, "profile_id");
            java.util.Objects.requireNonNull(expectedCapabilityRevision, "expected_capability_revision");
        }
    }
    /** ModelSelection 的已知字段；数组在构造时复制以固定快照。 */
    public record ModelSelection(
        @JsonProperty("model_id") String modelId,
        @JsonProperty("profile_id") String profileId,
        @JsonProperty("capability_revision") Long capabilityRevision
    ) {
        public ModelSelection {
            java.util.Objects.requireNonNull(modelId, "model_id");
            java.util.Objects.requireNonNull(profileId, "profile_id");
            java.util.Objects.requireNonNull(capabilityRevision, "capability_revision");
        }
    }
    /** ParameterOrigin 的已知字段；数组在构造时复制以固定快照。 */
    public record ParameterOrigin(
        @JsonProperty("field") ParameterKey field,
        @JsonProperty("source") ParameterOriginSource source,
        @JsonProperty("source_model") ModelSelection sourceModel
    ) {
        public ParameterOrigin {
            java.util.Objects.requireNonNull(field, "field");
            java.util.Objects.requireNonNull(source, "source");
        }
    }
    /** ImagePlanConfiguration 的已知字段；数组在构造时复制以固定快照。 */
    public record ImagePlanConfiguration(
        @JsonProperty("type") String type,
        @JsonProperty("model_selection") ModelSelection modelSelection,
        @JsonProperty("intent") ImageIntent intent,
        @JsonProperty("parameters") ImageParameters parameters,
        @JsonProperty("parameter_origins") List<ParameterOrigin> parameterOrigins
    ) implements PlanConfiguration {
        public ImagePlanConfiguration {
            java.util.Objects.requireNonNull(type, "type");
            if (!"image_plan_configuration".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(intent, "intent");
            java.util.Objects.requireNonNull(parameters, "parameters");
            java.util.Objects.requireNonNull(parameterOrigins, "parameter_origins");
            parameterOrigins = parameterOrigins == null ? null : List.copyOf(parameterOrigins);
        }
    }
    /** VideoPlanConfiguration 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoPlanConfiguration(
        @JsonProperty("type") String type,
        @JsonProperty("model_selection") ModelSelection modelSelection,
        @JsonProperty("intent") VideoIntent intent,
        @JsonProperty("parameters") VideoParameters parameters,
        @JsonProperty("parameter_origins") List<ParameterOrigin> parameterOrigins
    ) implements PlanConfiguration {
        public VideoPlanConfiguration {
            java.util.Objects.requireNonNull(type, "type");
            if (!"video_plan_configuration".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(intent, "intent");
            java.util.Objects.requireNonNull(parameters, "parameters");
            java.util.Objects.requireNonNull(parameterOrigins, "parameter_origins");
            parameterOrigins = parameterOrigins == null ? null : List.copyOf(parameterOrigins);
        }
    }
    /** PlanConfiguration 的封闭变体，禁止未知业务结构。 */
    public sealed interface PlanConfiguration permits ImagePlanConfiguration, VideoPlanConfiguration {
        String type();
        ModelSelection modelSelection();
        List<ParameterOrigin> parameterOrigins();
    }
    /** SizePreset 的已知字段；数组在构造时复制以固定快照。 */
    public record SizePreset(
        @JsonProperty("preset_id") String presetId,
        @JsonProperty("aspect_ratio") AspectRatio aspectRatio,
        @JsonProperty("resolution_id") String resolutionId,
        @JsonProperty("pixel_dimensions") PixelDimensions pixelDimensions
    ) {
        public SizePreset {
            java.util.Objects.requireNonNull(presetId, "preset_id");
            java.util.Objects.requireNonNull(aspectRatio, "aspect_ratio");
            java.util.Objects.requireNonNull(resolutionId, "resolution_id");
            java.util.Objects.requireNonNull(pixelDimensions, "pixel_dimensions");
        }
    }
    /** PixelBounds 的已知字段；数组在构造时复制以固定快照。 */
    public record PixelBounds(
        @JsonProperty("min_width") Integer minWidth,
        @JsonProperty("max_width") Integer maxWidth,
        @JsonProperty("min_height") Integer minHeight,
        @JsonProperty("max_height") Integer maxHeight,
        @JsonProperty("max_pixels") Long maxPixels
    ) {
        public PixelBounds {
            java.util.Objects.requireNonNull(minWidth, "min_width");
            java.util.Objects.requireNonNull(maxWidth, "max_width");
            java.util.Objects.requireNonNull(minHeight, "min_height");
            java.util.Objects.requireNonNull(maxHeight, "max_height");
            java.util.Objects.requireNonNull(maxPixels, "max_pixels");
        }
    }
    /** CustomPixelRules 的已知字段；数组在构造时复制以固定快照。 */
    public record CustomPixelRules(
        @JsonProperty("bounds") PixelBounds bounds,
        @JsonProperty("width_step") Integer widthStep,
        @JsonProperty("height_step") Integer heightStep,
        @JsonProperty("allowed_aspect_ratios") List<AspectRatio> allowedAspectRatios
    ) {
        public CustomPixelRules {
            java.util.Objects.requireNonNull(bounds, "bounds");
            java.util.Objects.requireNonNull(widthStep, "width_step");
            java.util.Objects.requireNonNull(heightStep, "height_step");
            java.util.Objects.requireNonNull(allowedAspectRatios, "allowed_aspect_ratios");
            allowedAspectRatios = allowedAspectRatios == null ? null : List.copyOf(allowedAspectRatios);
        }
    }
    /** SizeRules 的已知字段；数组在构造时复制以固定快照。 */
    public record SizeRules(
        @JsonProperty("presets") List<SizePreset> presets,
        @JsonProperty("custom_pixels") CustomPixelRules customPixels
    ) {
        public SizeRules {
            java.util.Objects.requireNonNull(presets, "presets");
            presets = presets == null ? null : List.copyOf(presets);
        }
    }
    /** DiscreteIntDomain 的已知字段；数组在构造时复制以固定快照。 */
    public record DiscreteIntDomain(
        @JsonProperty("type") String type,
        @JsonProperty("values") List<Integer> values
    ) implements PositiveIntDomain {
        public DiscreteIntDomain {
            java.util.Objects.requireNonNull(type, "type");
            if (!"discrete_int_domain".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(values, "values");
            values = values == null ? null : List.copyOf(values);
        }
    }
    /** SteppedIntDomain 的已知字段；数组在构造时复制以固定快照。 */
    public record SteppedIntDomain(
        @JsonProperty("type") String type,
        @JsonProperty("minimum") Integer minimum,
        @JsonProperty("maximum") Integer maximum,
        @JsonProperty("step") Integer step
    ) implements PositiveIntDomain {
        public SteppedIntDomain {
            java.util.Objects.requireNonNull(type, "type");
            if (!"stepped_int_domain".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(minimum, "minimum");
            java.util.Objects.requireNonNull(maximum, "maximum");
            java.util.Objects.requireNonNull(step, "step");
        }
    }
    /** PositiveIntDomain 的封闭变体，禁止未知业务结构。 */
    public sealed interface PositiveIntDomain permits DiscreteIntDomain, SteppedIntDomain {
        String type();
    }
    /** PromptRules 的已知字段；数组在构造时复制以固定快照。 */
    public record PromptRules(
        @JsonProperty("max_utf8_bytes") Integer maxUtf8Bytes
    ) {
        public PromptRules {
            java.util.Objects.requireNonNull(maxUtf8Bytes, "max_utf8_bytes");
        }
    }
    /** ReferenceRules 的已知字段；数组在构造时复制以固定快照。 */
    public record ReferenceRules(
        @JsonProperty("minimum_total_count") Integer minimumTotalCount,
        @JsonProperty("maximum_total_count") Integer maximumTotalCount,
        @JsonProperty("media_limits") List<ReferenceMediaLimit> mediaLimits,
        @JsonProperty("max_total_video_duration_milliseconds") Long maxTotalVideoDurationMilliseconds,
        @JsonProperty("max_total_audio_duration_milliseconds") Long maxTotalAudioDurationMilliseconds,
        @JsonProperty("max_input_video_plus_output_duration_milliseconds") Long maxInputVideoPlusOutputDurationMilliseconds
    ) {
        public ReferenceRules {
            java.util.Objects.requireNonNull(minimumTotalCount, "minimum_total_count");
            java.util.Objects.requireNonNull(maximumTotalCount, "maximum_total_count");
            java.util.Objects.requireNonNull(mediaLimits, "media_limits");
            mediaLimits = mediaLimits == null ? null : List.copyOf(mediaLimits);
        }
    }
    /** CapabilityEvidence 的已知字段；数组在构造时复制以固定快照。 */
    public record CapabilityEvidence(
        @JsonProperty("source_kind") CapabilityEvidenceSourceKind sourceKind,
        @JsonProperty("reference") String reference,
        @JsonProperty("checked_at") @JsonSerialize(using = GenerationPlanCodec.MicrosecondInstantSerializer.class) Instant checkedAt,
        @JsonProperty("note") String note
    ) {
        public CapabilityEvidence {
            java.util.Objects.requireNonNull(sourceKind, "source_kind");
            java.util.Objects.requireNonNull(reference, "reference");
            java.util.Objects.requireNonNull(checkedAt, "checked_at");
            java.util.Objects.requireNonNull(note, "note");
        }
    }
    /** ImageDefaults 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageDefaults(
        @JsonProperty("size") DraftOutputSize size,
        @JsonProperty("output_format") ImageOutputFormat outputFormat
    ) {
        public ImageDefaults {
        }
    }
    /** VideoDefaults 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoDefaults(
        @JsonProperty("size") DraftOutputSize size,
        @JsonProperty("output_format") VideoOutputFormat outputFormat,
        @JsonProperty("duration_seconds") Integer durationSeconds,
        @JsonProperty("generate_audio") Boolean generateAudio
    ) {
        public VideoDefaults {
        }
    }
    /** VideoOutputOption 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoOutputOption(
        @JsonProperty("size_preset_id") String sizePresetId,
        @JsonProperty("duration_seconds") PositiveIntDomain durationSeconds
    ) {
        public VideoOutputOption {
            java.util.Objects.requireNonNull(sizePresetId, "size_preset_id");
            java.util.Objects.requireNonNull(durationSeconds, "duration_seconds");
        }
    }
    /** VerifiedImageProfile 的已知字段；数组在构造时复制以固定快照。 */
    public record VerifiedImageProfile(
        @JsonProperty("type") String type,
        @JsonProperty("profile_id") String profileId,
        @JsonProperty("generation_mode") ImageMode generationMode,
        @JsonProperty("prompt_rules") PromptRules promptRules,
        @JsonProperty("size_rules") SizeRules sizeRules,
        @JsonProperty("reference_rules") ReferenceRules referenceRules,
        @JsonProperty("output_formats") List<ImageOutputFormat> outputFormats,
        @JsonProperty("defaults") ImageDefaults defaults,
        @JsonProperty("evidence") List<CapabilityEvidence> evidence
    ) implements ImageProfile {
        public VerifiedImageProfile {
            java.util.Objects.requireNonNull(type, "type");
            if (!"verified_image_profile".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(profileId, "profile_id");
            java.util.Objects.requireNonNull(generationMode, "generation_mode");
            java.util.Objects.requireNonNull(promptRules, "prompt_rules");
            java.util.Objects.requireNonNull(sizeRules, "size_rules");
            java.util.Objects.requireNonNull(referenceRules, "reference_rules");
            java.util.Objects.requireNonNull(outputFormats, "output_formats");
            outputFormats = outputFormats == null ? null : List.copyOf(outputFormats);
            java.util.Objects.requireNonNull(defaults, "defaults");
            java.util.Objects.requireNonNull(evidence, "evidence");
            evidence = evidence == null ? null : List.copyOf(evidence);
        }
    }
    /** VerifiedVideoProfile 的已知字段；数组在构造时复制以固定快照。 */
    public record VerifiedVideoProfile(
        @JsonProperty("type") String type,
        @JsonProperty("profile_id") String profileId,
        @JsonProperty("generation_mode") VideoMode generationMode,
        @JsonProperty("prompt_rules") PromptRules promptRules,
        @JsonProperty("size_rules") SizeRules sizeRules,
        @JsonProperty("output_options") List<VideoOutputOption> outputOptions,
        @JsonProperty("reference_rules") ReferenceRules referenceRules,
        @JsonProperty("output_formats") List<VideoOutputFormat> outputFormats,
        @JsonProperty("generate_audio_values") List<Boolean> generateAudioValues,
        @JsonProperty("duration_tolerance_milliseconds") Long durationToleranceMilliseconds,
        @JsonProperty("defaults") VideoDefaults defaults,
        @JsonProperty("evidence") List<CapabilityEvidence> evidence
    ) implements VideoProfile {
        public VerifiedVideoProfile {
            java.util.Objects.requireNonNull(type, "type");
            if (!"verified_video_profile".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(profileId, "profile_id");
            java.util.Objects.requireNonNull(generationMode, "generation_mode");
            java.util.Objects.requireNonNull(promptRules, "prompt_rules");
            java.util.Objects.requireNonNull(sizeRules, "size_rules");
            java.util.Objects.requireNonNull(outputOptions, "output_options");
            outputOptions = outputOptions == null ? null : List.copyOf(outputOptions);
            java.util.Objects.requireNonNull(referenceRules, "reference_rules");
            java.util.Objects.requireNonNull(outputFormats, "output_formats");
            outputFormats = outputFormats == null ? null : List.copyOf(outputFormats);
            java.util.Objects.requireNonNull(generateAudioValues, "generate_audio_values");
            generateAudioValues = generateAudioValues == null ? null : List.copyOf(generateAudioValues);
            java.util.Objects.requireNonNull(durationToleranceMilliseconds, "duration_tolerance_milliseconds");
            java.util.Objects.requireNonNull(defaults, "defaults");
            java.util.Objects.requireNonNull(evidence, "evidence");
            evidence = evidence == null ? null : List.copyOf(evidence);
        }
    }
    /** UnavailableImageProfile 的已知字段；数组在构造时复制以固定快照。 */
    public record UnavailableImageProfile(
        @JsonProperty("type") String type,
        @JsonProperty("profile_id") String profileId,
        @JsonProperty("generation_mode") ImageMode generationMode,
        @JsonProperty("status") UnavailableImageProfileStatus status,
        @JsonProperty("reason") String reason
    ) implements ImageProfile {
        public UnavailableImageProfile {
            java.util.Objects.requireNonNull(type, "type");
            if (!"unavailable_image_profile".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(profileId, "profile_id");
            java.util.Objects.requireNonNull(generationMode, "generation_mode");
            java.util.Objects.requireNonNull(status, "status");
            java.util.Objects.requireNonNull(reason, "reason");
        }
    }
    /** UnavailableVideoProfile 的已知字段；数组在构造时复制以固定快照。 */
    public record UnavailableVideoProfile(
        @JsonProperty("type") String type,
        @JsonProperty("profile_id") String profileId,
        @JsonProperty("generation_mode") VideoMode generationMode,
        @JsonProperty("status") UnavailableVideoProfileStatus status,
        @JsonProperty("reason") String reason
    ) implements VideoProfile {
        public UnavailableVideoProfile {
            java.util.Objects.requireNonNull(type, "type");
            if (!"unavailable_video_profile".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(profileId, "profile_id");
            java.util.Objects.requireNonNull(generationMode, "generation_mode");
            java.util.Objects.requireNonNull(status, "status");
            java.util.Objects.requireNonNull(reason, "reason");
        }
    }
    /** ImageProfile 的封闭变体，禁止未知业务结构。 */
    public sealed interface ImageProfile permits VerifiedImageProfile, UnavailableImageProfile {
        String type();
        String profileId();
        ImageMode generationMode();
    }
    /** VideoProfile 的封闭变体，禁止未知业务结构。 */
    public sealed interface VideoProfile permits VerifiedVideoProfile, UnavailableVideoProfile {
        String type();
        String profileId();
        VideoMode generationMode();
    }
    /** ImageModelDefinition 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageModelDefinition(
        @JsonProperty("type") String type,
        @JsonProperty("model_id") String modelId,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("capability_revision") Long capabilityRevision,
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("provider_binding") ProviderBinding providerBinding,
        @JsonProperty("profiles") List<ImageProfile> profiles
    ) implements ModelDefinition {
        public ImageModelDefinition {
            java.util.Objects.requireNonNull(type, "type");
            if (!"image_model_definition".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(modelId, "model_id");
            java.util.Objects.requireNonNull(displayName, "display_name");
            java.util.Objects.requireNonNull(capabilityRevision, "capability_revision");
            java.util.Objects.requireNonNull(enabled, "enabled");
            java.util.Objects.requireNonNull(providerBinding, "provider_binding");
            java.util.Objects.requireNonNull(profiles, "profiles");
            profiles = profiles == null ? null : List.copyOf(profiles);
        }
    }
    /** VideoModelDefinition 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoModelDefinition(
        @JsonProperty("type") String type,
        @JsonProperty("model_id") String modelId,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("capability_revision") Long capabilityRevision,
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("provider_binding") ProviderBinding providerBinding,
        @JsonProperty("profiles") List<VideoProfile> profiles
    ) implements ModelDefinition {
        public VideoModelDefinition {
            java.util.Objects.requireNonNull(type, "type");
            if (!"video_model_definition".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(modelId, "model_id");
            java.util.Objects.requireNonNull(displayName, "display_name");
            java.util.Objects.requireNonNull(capabilityRevision, "capability_revision");
            java.util.Objects.requireNonNull(enabled, "enabled");
            java.util.Objects.requireNonNull(providerBinding, "provider_binding");
            java.util.Objects.requireNonNull(profiles, "profiles");
            profiles = profiles == null ? null : List.copyOf(profiles);
        }
    }
    /** ModelDefinition 的封闭变体，禁止未知业务结构。 */
    public sealed interface ModelDefinition permits ImageModelDefinition, VideoModelDefinition {
        String type();
        String modelId();
        String displayName();
        Long capabilityRevision();
        Boolean enabled();
        ProviderBinding providerBinding();
    }
    /** ModelCatalogSnapshot 的已知字段；数组在构造时复制以固定快照。 */
    public record ModelCatalogSnapshot(
        @JsonProperty("type") String type,
        @JsonProperty("catalog_revision") Long catalogRevision,
        @JsonProperty("submission_policy") GenerationSubmissionPolicy submissionPolicy,
        @JsonProperty("models") List<ModelDefinition> models
    ) implements GenerationPlanToolResult {
        public ModelCatalogSnapshot {
            java.util.Objects.requireNonNull(type, "type");
            if (!"model_catalog_snapshot".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(catalogRevision, "catalog_revision");
            java.util.Objects.requireNonNull(submissionPolicy, "submission_policy");
            java.util.Objects.requireNonNull(models, "models");
            models = models == null ? null : List.copyOf(models);
        }
    }
    /** ResolvedPresetSize 的已知字段；数组在构造时复制以固定快照。 */
    public record ResolvedPresetSize(
        @JsonProperty("type") String type,
        @JsonProperty("preset") SizePreset preset
    ) implements ExecutableSize {
        public ResolvedPresetSize {
            java.util.Objects.requireNonNull(type, "type");
            if (!"resolved_preset_size".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(preset, "preset");
        }
    }
    /** ExecutableSize 的封闭变体，禁止未知业务结构。 */
    public sealed interface ExecutableSize permits ResolvedPresetSize, PixelSize {
        String type();
    }
    /** ImageExecutionParameters 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageExecutionParameters(
        @JsonProperty("type") String type,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("size") ExecutableSize size,
        @JsonProperty("references") List<ReferenceAsset> references,
        @JsonProperty("output_format") ImageOutputFormat outputFormat,
        @JsonProperty("generation_mode") ImageMode generationMode
    ) {
        public ImageExecutionParameters {
            java.util.Objects.requireNonNull(type, "type");
            if (!"image_execution_parameters".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(prompt, "prompt");
            java.util.Objects.requireNonNull(size, "size");
            java.util.Objects.requireNonNull(references, "references");
            references = references == null ? null : List.copyOf(references);
            java.util.Objects.requireNonNull(outputFormat, "output_format");
            java.util.Objects.requireNonNull(generationMode, "generation_mode");
        }
    }
    /** VideoExecutionParameters 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoExecutionParameters(
        @JsonProperty("type") String type,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("size") ResolvedPresetSize size,
        @JsonProperty("references") List<ReferenceAsset> references,
        @JsonProperty("output_format") VideoOutputFormat outputFormat,
        @JsonProperty("duration_seconds") Integer durationSeconds,
        @JsonProperty("generate_audio") Boolean generateAudio,
        @JsonProperty("generation_mode") VideoMode generationMode
    ) {
        public VideoExecutionParameters {
            java.util.Objects.requireNonNull(type, "type");
            if (!"video_execution_parameters".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(prompt, "prompt");
            java.util.Objects.requireNonNull(size, "size");
            java.util.Objects.requireNonNull(references, "references");
            references = references == null ? null : List.copyOf(references);
            java.util.Objects.requireNonNull(outputFormat, "output_format");
            java.util.Objects.requireNonNull(durationSeconds, "duration_seconds");
            java.util.Objects.requireNonNull(generateAudio, "generate_audio");
            java.util.Objects.requireNonNull(generationMode, "generation_mode");
        }
    }
    /** ImageResultExpectations 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageResultExpectations(
        @JsonProperty("type") String type,
        @JsonProperty("file_count") Integer fileCount,
        @JsonProperty("size") ExecutableSize size,
        @JsonProperty("output_format") ImageOutputFormat outputFormat
    ) {
        public ImageResultExpectations {
            java.util.Objects.requireNonNull(type, "type");
            if (!"image_result_expectations".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(fileCount, "file_count");
            if (!fileCount.equals(1)) throw new IllegalArgumentException("Invalid file_count");
            java.util.Objects.requireNonNull(size, "size");
            java.util.Objects.requireNonNull(outputFormat, "output_format");
        }
    }
    /** VideoResultExpectations 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoResultExpectations(
        @JsonProperty("type") String type,
        @JsonProperty("file_count") Integer fileCount,
        @JsonProperty("size") ResolvedPresetSize size,
        @JsonProperty("output_format") VideoOutputFormat outputFormat,
        @JsonProperty("duration_seconds") Integer durationSeconds,
        @JsonProperty("duration_tolerance_milliseconds") Long durationToleranceMilliseconds,
        @JsonProperty("expected_audio") Boolean expectedAudio
    ) {
        public VideoResultExpectations {
            java.util.Objects.requireNonNull(type, "type");
            if (!"video_result_expectations".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(fileCount, "file_count");
            if (!fileCount.equals(1)) throw new IllegalArgumentException("Invalid file_count");
            java.util.Objects.requireNonNull(size, "size");
            java.util.Objects.requireNonNull(outputFormat, "output_format");
            java.util.Objects.requireNonNull(durationSeconds, "duration_seconds");
            java.util.Objects.requireNonNull(durationToleranceMilliseconds, "duration_tolerance_milliseconds");
            java.util.Objects.requireNonNull(expectedAudio, "expected_audio");
        }
    }
    /** ImageGenerationRequest 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageGenerationRequest(
        @JsonProperty("request_type") String requestType,
        @JsonProperty("schema_version") Integer schemaVersion,
        @JsonProperty("model_selection") ModelSelection modelSelection,
        @JsonProperty("provider_binding") ProviderBinding providerBinding,
        @JsonProperty("parameters") ImageExecutionParameters parameters,
        @JsonProperty("result_expectations") ImageResultExpectations resultExpectations
    ) implements NormalizedGenerationRequest {
        public ImageGenerationRequest {
            java.util.Objects.requireNonNull(requestType, "request_type");
            if (!"image_generation_plan_v1".equals(requestType)) throw new IllegalArgumentException("Invalid request_type");
            java.util.Objects.requireNonNull(schemaVersion, "schema_version");
            if (!schemaVersion.equals(1)) throw new IllegalArgumentException("Invalid schema_version");
            java.util.Objects.requireNonNull(modelSelection, "model_selection");
            java.util.Objects.requireNonNull(providerBinding, "provider_binding");
            java.util.Objects.requireNonNull(parameters, "parameters");
            java.util.Objects.requireNonNull(resultExpectations, "result_expectations");
        }
    }
    /** VideoGenerationRequest 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoGenerationRequest(
        @JsonProperty("request_type") String requestType,
        @JsonProperty("schema_version") Integer schemaVersion,
        @JsonProperty("model_selection") ModelSelection modelSelection,
        @JsonProperty("provider_binding") ProviderBinding providerBinding,
        @JsonProperty("parameters") VideoExecutionParameters parameters,
        @JsonProperty("result_expectations") VideoResultExpectations resultExpectations
    ) implements NormalizedGenerationRequest {
        public VideoGenerationRequest {
            java.util.Objects.requireNonNull(requestType, "request_type");
            if (!"video_generation_plan_v1".equals(requestType)) throw new IllegalArgumentException("Invalid request_type");
            java.util.Objects.requireNonNull(schemaVersion, "schema_version");
            if (!schemaVersion.equals(1)) throw new IllegalArgumentException("Invalid schema_version");
            java.util.Objects.requireNonNull(modelSelection, "model_selection");
            java.util.Objects.requireNonNull(providerBinding, "provider_binding");
            java.util.Objects.requireNonNull(parameters, "parameters");
            java.util.Objects.requireNonNull(resultExpectations, "result_expectations");
        }
    }
    /** NormalizedGenerationRequest 的封闭变体，禁止未知业务结构。 */
    public sealed interface NormalizedGenerationRequest permits ImageGenerationRequest, VideoGenerationRequest {
        String requestType();
        Integer schemaVersion();
        ModelSelection modelSelection();
        ProviderBinding providerBinding();
    }
    /** GenerationPlan 的已知字段；数组在构造时复制以固定快照。 */
    public record GenerationPlan(
        @JsonProperty("type") String type,
        @JsonProperty("schema_version") Integer schemaVersion,
        @JsonProperty("id") UUID id,
        @JsonProperty("user_id") UUID userId,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("revision") Long revision,
        @JsonProperty("state") PlanState state,
        @JsonProperty("configuration") PlanConfiguration configuration,
        @JsonProperty("frozen_request") NormalizedGenerationRequest frozenRequest,
        @JsonProperty("created_at") @JsonSerialize(using = GenerationPlanCodec.MicrosecondInstantSerializer.class) Instant createdAt,
        @JsonProperty("updated_at") @JsonSerialize(using = GenerationPlanCodec.MicrosecondInstantSerializer.class) Instant updatedAt,
        @JsonProperty("frozen_at") @JsonSerialize(using = GenerationPlanCodec.MicrosecondInstantSerializer.class) Instant frozenAt
    ) {
        public GenerationPlan {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(schemaVersion, "schema_version");
            if (!schemaVersion.equals(1)) throw new IllegalArgumentException("Invalid schema_version");
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(userId, "user_id");
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(revision, "revision");
            java.util.Objects.requireNonNull(state, "state");
            java.util.Objects.requireNonNull(configuration, "configuration");
            java.util.Objects.requireNonNull(createdAt, "created_at");
            java.util.Objects.requireNonNull(updatedAt, "updated_at");
        }
    }
    /** SessionPlanFocus 的已知字段；数组在构造时复制以固定快照。 */
    public record SessionPlanFocus(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("active_plan_id") UUID activePlanId,
        @JsonProperty("revision") Long revision
    ) {
        public SessionPlanFocus {
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(revision, "revision");
        }
    }
    /** IssueCode 的固定协议值。 */
    public enum IssueCode { MODEL_REQUIRED, MODEL_DISABLED, MODEL_NOT_FOUND, CAPABILITY_REVISION_UNAVAILABLE, CAPABILITIES_CHANGED, PROFILE_UNKNOWN, PROFILE_UNSUPPORTED, REQUIRED_PARAMETER_MISSING, PARAMETER_UNSUPPORTED, REQUIREMENT_UNSATISFIED, REFERENCE_UNAVAILABLE, REFERENCE_TYPE_MISMATCH, REFERENCE_METADATA_UNAVAILABLE, REFERENCE_LIMIT_EXCEEDED, REFERENCE_COMBINATION_UNSUPPORTED, SIZE_DURATION_COMBINATION_UNSUPPORTED, EXECUTION_ADAPTER_UNAVAILABLE, RESULT_VERIFIER_UNAVAILABLE, REQUESTED_FILE_COUNT_UNSUPPORTED }
    /** IssueField 的固定协议值。 */
    public enum IssueField { MODEL, PROFILE, GENERATION_MODE, INTENT_DESCRIPTION, PROMPT, ASPECT_RATIO, RESOLUTION_ID, PIXEL_DIMENSIONS, REQUESTED_FILE_COUNT, REFERENCES, OUTPUT_FORMAT, DURATION_SECONDS, GENERATE_AUDIO, PROVIDER_BINDING }
    /** ValidationIssue 的已知字段；数组在构造时复制以固定快照。 */
    public record ValidationIssue(
        @JsonProperty("code") IssueCode code,
        @JsonProperty("field") IssueField field,
        @JsonProperty("reference_index") Integer referenceIndex,
        @JsonProperty("message") String message,
        @JsonProperty("model_selection") ModelSelection modelSelection
    ) {
        public ValidationIssue {
            java.util.Objects.requireNonNull(code, "code");
            java.util.Objects.requireNonNull(field, "field");
            java.util.Objects.requireNonNull(message, "message");
        }
    }
    /** PlanValidation 的已知字段；数组在构造时复制以固定快照。 */
    public record PlanValidation(
        @JsonProperty("can_submit") Boolean canSubmit,
        @JsonProperty("issues") List<ValidationIssue> issues,
        @JsonProperty("validated_catalog_revision") Long validatedCatalogRevision
    ) {
        public PlanValidation {
            java.util.Objects.requireNonNull(canSubmit, "can_submit");
            java.util.Objects.requireNonNull(issues, "issues");
            issues = issues == null ? null : List.copyOf(issues);
            java.util.Objects.requireNonNull(validatedCatalogRevision, "validated_catalog_revision");
        }
    }
    /** GenerationPlanSnapshot 的已知字段；数组在构造时复制以固定快照。 */
    public record GenerationPlanSnapshot(
        @JsonProperty("plan") GenerationPlan plan,
        @JsonProperty("validation") PlanValidation validation
    ) {
        public GenerationPlanSnapshot {
            java.util.Objects.requireNonNull(plan, "plan");
            java.util.Objects.requireNonNull(validation, "validation");
        }
    }
    /** PlansCursor 的已知字段；数组在构造时复制以固定快照。 */
    public record PlansCursor(
        @JsonProperty("before_created_at") @JsonSerialize(using = GenerationPlanCodec.MicrosecondInstantSerializer.class) Instant beforeCreatedAt,
        @JsonProperty("before_id") UUID beforeId
    ) {
        public PlansCursor {
            java.util.Objects.requireNonNull(beforeCreatedAt, "before_created_at");
            java.util.Objects.requireNonNull(beforeId, "before_id");
        }
    }
    /** FocusedPlanQuery 的已知字段；数组在构造时复制以固定快照。 */
    public record FocusedPlanQuery(
        @JsonProperty("type") String type
    ) implements PlanQuery {
        public FocusedPlanQuery {
            java.util.Objects.requireNonNull(type, "type");
            if (!"focused".equals(type)) throw new IllegalArgumentException("Invalid type");
        }
    }
    /** PlanByIdQuery 的已知字段；数组在构造时复制以固定快照。 */
    public record PlanByIdQuery(
        @JsonProperty("type") String type,
        @JsonProperty("plan_id") UUID planId
    ) implements PlanQuery {
        public PlanByIdQuery {
            java.util.Objects.requireNonNull(type, "type");
            if (!"by_id".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(planId, "plan_id");
        }
    }
    /** SessionPlansQuery 的已知字段；数组在构造时复制以固定快照。 */
    public record SessionPlansQuery(
        @JsonProperty("type") String type,
        @JsonProperty("cursor") PlansCursor cursor,
        @JsonProperty("limit") Integer limit
    ) implements PlanQuery {
        public SessionPlansQuery {
            java.util.Objects.requireNonNull(type, "type");
            if (!"list".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(limit, "limit");
        }
    }
    /** PlanQuery 的封闭变体，禁止未知业务结构。 */
    public sealed interface PlanQuery permits FocusedPlanQuery, PlanByIdQuery, SessionPlansQuery {
        String type();
    }
    /** ImageTarget 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageTarget(
        @JsonProperty("media_type") String mediaType,
        @JsonProperty("model_choice") ModelChoice modelChoice
    ) implements GenerationTarget {
        public ImageTarget {
            java.util.Objects.requireNonNull(mediaType, "media_type");
            if (!"IMAGE".equals(mediaType)) throw new IllegalArgumentException("Invalid media_type");
        }
    }
    /** VideoTarget 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoTarget(
        @JsonProperty("media_type") String mediaType,
        @JsonProperty("model_choice") ModelChoice modelChoice
    ) implements GenerationTarget {
        public VideoTarget {
            java.util.Objects.requireNonNull(mediaType, "media_type");
            if (!"VIDEO".equals(mediaType)) throw new IllegalArgumentException("Invalid media_type");
        }
    }
    /** GenerationTarget 的封闭变体，禁止未知业务结构。 */
    public sealed interface GenerationTarget permits ImageTarget, VideoTarget {
        String mediaType();
        ModelChoice modelChoice();
    }
    /** KeepChange 的已知字段；数组在构造时复制以固定快照。 */
    public record KeepChange(
        @JsonProperty("op") String op
    ) implements DescriptionChange, PromptChange, SizeChange, ImageOutputFormatChange, VideoOutputFormatChange, DurationChange, BooleanChange, ImageModeChange, VideoModeChange, RequestedFileCountChange, ReferencesChange {
        public KeepChange {
            java.util.Objects.requireNonNull(op, "op");
            if (!"KEEP".equals(op)) throw new IllegalArgumentException("Invalid op");
        }
    }
    /** ClearChange 的已知字段；数组在构造时复制以固定快照。 */
    public record ClearChange(
        @JsonProperty("op") String op
    ) implements PromptChange, SizeChange, ImageOutputFormatChange, VideoOutputFormatChange, DurationChange, BooleanChange, ImageModeChange, VideoModeChange, RequestedFileCountChange, ReferencesChange {
        public ClearChange {
            java.util.Objects.requireNonNull(op, "op");
            if (!"CLEAR".equals(op)) throw new IllegalArgumentException("Invalid op");
        }
    }
    /** SetDescription 的已知字段；数组在构造时复制以固定快照。 */
    public record SetDescription(
        @JsonProperty("op") String op,
        @JsonProperty("value") String value
    ) implements DescriptionChange {
        public SetDescription {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
        }
    }
    /** DescriptionChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface DescriptionChange permits KeepChange, SetDescription {
        String op();
    }
    /** SetPrompt 的已知字段；数组在构造时复制以固定快照。 */
    public record SetPrompt(
        @JsonProperty("op") String op,
        @JsonProperty("value") String value
    ) implements PromptChange {
        public SetPrompt {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
        }
    }
    /** PromptChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface PromptChange permits KeepChange, SetPrompt, ClearChange {
        String op();
    }
    /** SetSize 的已知字段；数组在构造时复制以固定快照。 */
    public record SetSize(
        @JsonProperty("op") String op,
        @JsonProperty("value") DraftOutputSize value
    ) implements SizeChange {
        public SetSize {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
        }
    }
    /** SizeChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface SizeChange permits KeepChange, SetSize, ClearChange {
        String op();
    }
    /** SetImageOutputFormat 的已知字段；数组在构造时复制以固定快照。 */
    public record SetImageOutputFormat(
        @JsonProperty("op") String op,
        @JsonProperty("value") ImageOutputFormat value
    ) implements ImageOutputFormatChange {
        public SetImageOutputFormat {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
        }
    }
    /** ImageOutputFormatChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface ImageOutputFormatChange permits KeepChange, SetImageOutputFormat, ClearChange {
        String op();
    }
    /** SetVideoOutputFormat 的已知字段；数组在构造时复制以固定快照。 */
    public record SetVideoOutputFormat(
        @JsonProperty("op") String op,
        @JsonProperty("value") VideoOutputFormat value
    ) implements VideoOutputFormatChange {
        public SetVideoOutputFormat {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
        }
    }
    /** VideoOutputFormatChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface VideoOutputFormatChange permits KeepChange, SetVideoOutputFormat, ClearChange {
        String op();
    }
    /** SetDuration 的已知字段；数组在构造时复制以固定快照。 */
    public record SetDuration(
        @JsonProperty("op") String op,
        @JsonProperty("value") Integer value
    ) implements DurationChange {
        public SetDuration {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
        }
    }
    /** DurationChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface DurationChange permits KeepChange, SetDuration, ClearChange {
        String op();
    }
    /** SetBoolean 的已知字段；数组在构造时复制以固定快照。 */
    public record SetBoolean(
        @JsonProperty("op") String op,
        @JsonProperty("value") Boolean value
    ) implements BooleanChange {
        public SetBoolean {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
        }
    }
    /** BooleanChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface BooleanChange permits KeepChange, SetBoolean, ClearChange {
        String op();
    }
    /** SetImageMode 的已知字段；数组在构造时复制以固定快照。 */
    public record SetImageMode(
        @JsonProperty("op") String op,
        @JsonProperty("value") ImageMode value
    ) implements ImageModeChange {
        public SetImageMode {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
        }
    }
    /** ImageModeChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface ImageModeChange permits KeepChange, SetImageMode, ClearChange {
        String op();
    }
    /** SetVideoMode 的已知字段；数组在构造时复制以固定快照。 */
    public record SetVideoMode(
        @JsonProperty("op") String op,
        @JsonProperty("value") VideoMode value
    ) implements VideoModeChange {
        public SetVideoMode {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
        }
    }
    /** VideoModeChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface VideoModeChange permits KeepChange, SetVideoMode, ClearChange {
        String op();
    }
    /** ImageParametersPatch 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageParametersPatch(
        @JsonProperty("prompt") PromptChange prompt,
        @JsonProperty("size") SizeChange size,
        @JsonProperty("references") ReferencesChange references,
        @JsonProperty("output_format") ImageOutputFormatChange outputFormat
    ) {
        public ImageParametersPatch {
            java.util.Objects.requireNonNull(prompt, "prompt");
            java.util.Objects.requireNonNull(size, "size");
            java.util.Objects.requireNonNull(references, "references");
            java.util.Objects.requireNonNull(outputFormat, "output_format");
        }
    }
    /** VideoParametersPatch 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoParametersPatch(
        @JsonProperty("prompt") PromptChange prompt,
        @JsonProperty("size") SizeChange size,
        @JsonProperty("references") ReferencesChange references,
        @JsonProperty("output_format") VideoOutputFormatChange outputFormat,
        @JsonProperty("duration_seconds") DurationChange durationSeconds,
        @JsonProperty("generate_audio") BooleanChange generateAudio
    ) {
        public VideoParametersPatch {
            java.util.Objects.requireNonNull(prompt, "prompt");
            java.util.Objects.requireNonNull(size, "size");
            java.util.Objects.requireNonNull(references, "references");
            java.util.Objects.requireNonNull(outputFormat, "output_format");
            java.util.Objects.requireNonNull(durationSeconds, "duration_seconds");
            java.util.Objects.requireNonNull(generateAudio, "generate_audio");
        }
    }
    /** ImageRequirementsPatch 的已知字段；数组在构造时复制以固定快照。 */
    public record ImageRequirementsPatch(
        @JsonProperty("prompt") PromptChange prompt,
        @JsonProperty("size") SizeChange size,
        @JsonProperty("requested_file_count") RequestedFileCountChange requestedFileCount,
        @JsonProperty("references") ReferencesChange references,
        @JsonProperty("output_format") ImageOutputFormatChange outputFormat,
        @JsonProperty("generation_mode") ImageModeChange generationMode
    ) {
        public ImageRequirementsPatch {
            java.util.Objects.requireNonNull(prompt, "prompt");
            java.util.Objects.requireNonNull(size, "size");
            java.util.Objects.requireNonNull(requestedFileCount, "requested_file_count");
            java.util.Objects.requireNonNull(references, "references");
            java.util.Objects.requireNonNull(outputFormat, "output_format");
            java.util.Objects.requireNonNull(generationMode, "generation_mode");
        }
    }
    /** VideoRequirementsPatch 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoRequirementsPatch(
        @JsonProperty("prompt") PromptChange prompt,
        @JsonProperty("size") SizeChange size,
        @JsonProperty("requested_file_count") RequestedFileCountChange requestedFileCount,
        @JsonProperty("references") ReferencesChange references,
        @JsonProperty("output_format") VideoOutputFormatChange outputFormat,
        @JsonProperty("duration_seconds") DurationChange durationSeconds,
        @JsonProperty("generate_audio") BooleanChange generateAudio,
        @JsonProperty("generation_mode") VideoModeChange generationMode
    ) {
        public VideoRequirementsPatch {
            java.util.Objects.requireNonNull(prompt, "prompt");
            java.util.Objects.requireNonNull(size, "size");
            java.util.Objects.requireNonNull(requestedFileCount, "requested_file_count");
            java.util.Objects.requireNonNull(references, "references");
            java.util.Objects.requireNonNull(outputFormat, "output_format");
            java.util.Objects.requireNonNull(durationSeconds, "duration_seconds");
            java.util.Objects.requireNonNull(generateAudio, "generate_audio");
            java.util.Objects.requireNonNull(generationMode, "generation_mode");
        }
    }
    /** ImagePlanPatch 的已知字段；数组在构造时复制以固定快照。 */
    public record ImagePlanPatch(
        @JsonProperty("type") String type,
        @JsonProperty("description") DescriptionChange description,
        @JsonProperty("requirements") ImageRequirementsPatch requirements,
        @JsonProperty("parameters") ImageParametersPatch parameters
    ) implements PlanPatch {
        public ImagePlanPatch {
            java.util.Objects.requireNonNull(type, "type");
            if (!"image_plan_patch".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(description, "description");
            java.util.Objects.requireNonNull(requirements, "requirements");
            java.util.Objects.requireNonNull(parameters, "parameters");
        }
    }
    /** VideoPlanPatch 的已知字段；数组在构造时复制以固定快照。 */
    public record VideoPlanPatch(
        @JsonProperty("type") String type,
        @JsonProperty("description") DescriptionChange description,
        @JsonProperty("requirements") VideoRequirementsPatch requirements,
        @JsonProperty("parameters") VideoParametersPatch parameters
    ) implements PlanPatch {
        public VideoPlanPatch {
            java.util.Objects.requireNonNull(type, "type");
            if (!"video_plan_patch".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(description, "description");
            java.util.Objects.requireNonNull(requirements, "requirements");
            java.util.Objects.requireNonNull(parameters, "parameters");
        }
    }
    /** PlanPatch 的封闭变体，禁止未知业务结构。 */
    public sealed interface PlanPatch permits ImagePlanPatch, VideoPlanPatch {
        String type();
        DescriptionChange description();
    }
    /** GetModelCatalogArguments 的已知字段；数组在构造时复制以固定快照。 */
    public record GetModelCatalogArguments(
        @JsonProperty("media_type") MediaType mediaType
    ) {
        public GetModelCatalogArguments {
        }
    }
    /** ReadGenerationPlanArguments 的已知字段；数组在构造时复制以固定快照。 */
    public record ReadGenerationPlanArguments(
        @JsonProperty("query") PlanQuery query
    ) {
        public ReadGenerationPlanArguments {
            java.util.Objects.requireNonNull(query, "query");
        }
    }
    /** CreateImagePlanArguments 的已知字段；数组在构造时复制以固定快照。 */
    public record CreateImagePlanArguments(
        @JsonProperty("target") ImageTarget target,
        @JsonProperty("intent") ImageIntent intent,
        @JsonProperty("parameters") ImageParameters parameters,
        @JsonProperty("expected_focus_revision") Long expectedFocusRevision
    ) implements CreateGenerationPlanArguments {
        public CreateImagePlanArguments {
            java.util.Objects.requireNonNull(target, "target");
            java.util.Objects.requireNonNull(intent, "intent");
            java.util.Objects.requireNonNull(parameters, "parameters");
            java.util.Objects.requireNonNull(expectedFocusRevision, "expected_focus_revision");
        }
    }
    /** CreateVideoPlanArguments 的已知字段；数组在构造时复制以固定快照。 */
    public record CreateVideoPlanArguments(
        @JsonProperty("target") VideoTarget target,
        @JsonProperty("intent") VideoIntent intent,
        @JsonProperty("parameters") VideoParameters parameters,
        @JsonProperty("expected_focus_revision") Long expectedFocusRevision
    ) implements CreateGenerationPlanArguments {
        public CreateVideoPlanArguments {
            java.util.Objects.requireNonNull(target, "target");
            java.util.Objects.requireNonNull(intent, "intent");
            java.util.Objects.requireNonNull(parameters, "parameters");
            java.util.Objects.requireNonNull(expectedFocusRevision, "expected_focus_revision");
        }
    }
    /** CreateGenerationPlanArguments 的封闭变体，禁止未知业务结构。 */
    public sealed interface CreateGenerationPlanArguments permits CreateImagePlanArguments, CreateVideoPlanArguments {
        Long expectedFocusRevision();
    }
    /** UpdateGenerationPlanArguments 的已知字段；数组在构造时复制以固定快照。 */
    public record UpdateGenerationPlanArguments(
        @JsonProperty("plan_id") UUID planId,
        @JsonProperty("expected_plan_revision") Long expectedPlanRevision,
        @JsonProperty("expected_focus_revision") Long expectedFocusRevision,
        @JsonProperty("patch") PlanPatch patch
    ) {
        public UpdateGenerationPlanArguments {
            java.util.Objects.requireNonNull(planId, "plan_id");
            java.util.Objects.requireNonNull(expectedPlanRevision, "expected_plan_revision");
            java.util.Objects.requireNonNull(expectedFocusRevision, "expected_focus_revision");
            java.util.Objects.requireNonNull(patch, "patch");
        }
    }
    /** ChangeGenerationTargetArguments 的已知字段；数组在构造时复制以固定快照。 */
    public record ChangeGenerationTargetArguments(
        @JsonProperty("plan_id") UUID planId,
        @JsonProperty("expected_plan_revision") Long expectedPlanRevision,
        @JsonProperty("expected_focus_revision") Long expectedFocusRevision,
        @JsonProperty("target") GenerationTarget target
    ) {
        public ChangeGenerationTargetArguments {
            java.util.Objects.requireNonNull(planId, "plan_id");
            java.util.Objects.requireNonNull(expectedPlanRevision, "expected_plan_revision");
            java.util.Objects.requireNonNull(expectedFocusRevision, "expected_focus_revision");
            java.util.Objects.requireNonNull(target, "target");
        }
    }
    /** CloneGenerationPlanArguments 的已知字段；数组在构造时复制以固定快照。 */
    public record CloneGenerationPlanArguments(
        @JsonProperty("source_plan_id") UUID sourcePlanId,
        @JsonProperty("expected_source_plan_revision") Long expectedSourcePlanRevision,
        @JsonProperty("expected_focus_revision") Long expectedFocusRevision
    ) {
        public CloneGenerationPlanArguments {
            java.util.Objects.requireNonNull(sourcePlanId, "source_plan_id");
            java.util.Objects.requireNonNull(expectedSourcePlanRevision, "expected_source_plan_revision");
            java.util.Objects.requireNonNull(expectedFocusRevision, "expected_focus_revision");
        }
    }
    /** FocusGenerationPlanArguments 的已知字段；数组在构造时复制以固定快照。 */
    public record FocusGenerationPlanArguments(
        @JsonProperty("plan_id") UUID planId,
        @JsonProperty("expected_focus_revision") Long expectedFocusRevision
    ) {
        public FocusGenerationPlanArguments {
            java.util.Objects.requireNonNull(planId, "plan_id");
            java.util.Objects.requireNonNull(expectedFocusRevision, "expected_focus_revision");
        }
    }
    /** SubmitGenerationPlanArguments 的已知字段；数组在构造时复制以固定快照。 */
    public record SubmitGenerationPlanArguments(
        @JsonProperty("plan_id") UUID planId,
        @JsonProperty("expected_plan_revision") Long expectedPlanRevision,
        @JsonProperty("expected_focus_revision") Long expectedFocusRevision
    ) {
        public SubmitGenerationPlanArguments {
            java.util.Objects.requireNonNull(planId, "plan_id");
            java.util.Objects.requireNonNull(expectedPlanRevision, "expected_plan_revision");
            java.util.Objects.requireNonNull(expectedFocusRevision, "expected_focus_revision");
        }
    }
    /** GenerationPlanToolName 的固定协议值。 */
    public enum GenerationPlanToolName { get_model_catalog, read_generation_plan, create_generation_plan, update_generation_plan, change_generation_target, clone_generation_plan, focus_generation_plan, submit_generation_plan }
    /** GetModelCatalogInvocation 的已知字段；数组在构造时复制以固定快照。 */
    public record GetModelCatalogInvocation(
        @JsonProperty("type") String type,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("arguments") GetModelCatalogArguments arguments
    ) implements JavaPlanToolInvocation {
        public GetModelCatalogInvocation {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_tool_invocation".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(operationId, "operation_id");
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(toolName, "tool_name");
            if (!"get_model_catalog".equals(toolName)) throw new IllegalArgumentException("Invalid tool_name");
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }
    /** ReadGenerationPlanInvocation 的已知字段；数组在构造时复制以固定快照。 */
    public record ReadGenerationPlanInvocation(
        @JsonProperty("type") String type,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("arguments") ReadGenerationPlanArguments arguments
    ) implements JavaPlanToolInvocation {
        public ReadGenerationPlanInvocation {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_tool_invocation".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(operationId, "operation_id");
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(toolName, "tool_name");
            if (!"read_generation_plan".equals(toolName)) throw new IllegalArgumentException("Invalid tool_name");
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }
    /** CreateGenerationPlanInvocation 的已知字段；数组在构造时复制以固定快照。 */
    public record CreateGenerationPlanInvocation(
        @JsonProperty("type") String type,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("arguments") CreateGenerationPlanArguments arguments
    ) implements JavaPlanToolInvocation {
        public CreateGenerationPlanInvocation {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_tool_invocation".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(operationId, "operation_id");
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(toolName, "tool_name");
            if (!"create_generation_plan".equals(toolName)) throw new IllegalArgumentException("Invalid tool_name");
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }
    /** UpdateGenerationPlanInvocation 的已知字段；数组在构造时复制以固定快照。 */
    public record UpdateGenerationPlanInvocation(
        @JsonProperty("type") String type,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("arguments") UpdateGenerationPlanArguments arguments
    ) implements JavaPlanToolInvocation {
        public UpdateGenerationPlanInvocation {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_tool_invocation".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(operationId, "operation_id");
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(toolName, "tool_name");
            if (!"update_generation_plan".equals(toolName)) throw new IllegalArgumentException("Invalid tool_name");
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }
    /** ChangeGenerationTargetInvocation 的已知字段；数组在构造时复制以固定快照。 */
    public record ChangeGenerationTargetInvocation(
        @JsonProperty("type") String type,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("arguments") ChangeGenerationTargetArguments arguments
    ) implements JavaPlanToolInvocation {
        public ChangeGenerationTargetInvocation {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_tool_invocation".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(operationId, "operation_id");
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(toolName, "tool_name");
            if (!"change_generation_target".equals(toolName)) throw new IllegalArgumentException("Invalid tool_name");
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }
    /** CloneGenerationPlanInvocation 的已知字段；数组在构造时复制以固定快照。 */
    public record CloneGenerationPlanInvocation(
        @JsonProperty("type") String type,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("arguments") CloneGenerationPlanArguments arguments
    ) implements JavaPlanToolInvocation {
        public CloneGenerationPlanInvocation {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_tool_invocation".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(operationId, "operation_id");
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(toolName, "tool_name");
            if (!"clone_generation_plan".equals(toolName)) throw new IllegalArgumentException("Invalid tool_name");
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }
    /** FocusGenerationPlanInvocation 的已知字段；数组在构造时复制以固定快照。 */
    public record FocusGenerationPlanInvocation(
        @JsonProperty("type") String type,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("arguments") FocusGenerationPlanArguments arguments
    ) implements JavaPlanToolInvocation {
        public FocusGenerationPlanInvocation {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_tool_invocation".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(operationId, "operation_id");
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(toolName, "tool_name");
            if (!"focus_generation_plan".equals(toolName)) throw new IllegalArgumentException("Invalid tool_name");
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }
    /** SubmitGenerationPlanInvocation 的已知字段；数组在构造时复制以固定快照。 */
    public record SubmitGenerationPlanInvocation(
        @JsonProperty("type") String type,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("arguments") SubmitGenerationPlanArguments arguments
    ) implements JavaPlanToolInvocation {
        public SubmitGenerationPlanInvocation {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_tool_invocation".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(operationId, "operation_id");
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(toolName, "tool_name");
            if (!"submit_generation_plan".equals(toolName)) throw new IllegalArgumentException("Invalid tool_name");
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }
    /** JavaPlanToolInvocation 的封闭变体，禁止未知业务结构。 */
    public sealed interface JavaPlanToolInvocation permits GetModelCatalogInvocation, ReadGenerationPlanInvocation, CreateGenerationPlanInvocation, UpdateGenerationPlanInvocation, ChangeGenerationTargetInvocation, CloneGenerationPlanInvocation, FocusGenerationPlanInvocation, SubmitGenerationPlanInvocation {
        String type();
        UUID operationId();
        UUID sessionId();
        String toolName();
    }
    /** GenerationPlanReadResult 的已知字段；数组在构造时复制以固定快照。 */
    public record GenerationPlanReadResult(
        @JsonProperty("type") String type,
        @JsonProperty("focus") SessionPlanFocus focus,
        @JsonProperty("plans") List<GenerationPlanSnapshot> plans,
        @JsonProperty("next_cursor") PlansCursor nextCursor
    ) implements GenerationPlanToolResult {
        public GenerationPlanReadResult {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_read_result".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(focus, "focus");
            java.util.Objects.requireNonNull(plans, "plans");
            plans = plans == null ? null : List.copyOf(plans);
        }
    }
    /** AppliedDefault 的已知字段；数组在构造时复制以固定快照。 */
    public record AppliedDefault(
        @JsonProperty("field") ParameterKey field,
        @JsonProperty("source_model") ModelSelection sourceModel
    ) {
        public AppliedDefault {
            java.util.Objects.requireNonNull(field, "field");
            java.util.Objects.requireNonNull(sourceModel, "source_model");
        }
    }
    /** PlanMutationReceipt 的已知字段；数组在构造时复制以固定快照。 */
    public record PlanMutationReceipt(
        @JsonProperty("type") String type,
        @JsonProperty("action") PlanMutationReceiptAction action,
        @JsonProperty("source_plan_id") UUID sourcePlanId,
        @JsonProperty("snapshot") GenerationPlanSnapshot snapshot,
        @JsonProperty("focus") SessionPlanFocus focus,
        @JsonProperty("cleared_execution_fields") List<ParameterKey> clearedExecutionFields,
        @JsonProperty("non_migrated_requirement_fields") List<IssueField> nonMigratedRequirementFields,
        @JsonProperty("applied_defaults") List<AppliedDefault> appliedDefaults
    ) implements GenerationPlanToolResult {
        public PlanMutationReceipt {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_mutation_receipt".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(action, "action");
            java.util.Objects.requireNonNull(snapshot, "snapshot");
            java.util.Objects.requireNonNull(focus, "focus");
            java.util.Objects.requireNonNull(clearedExecutionFields, "cleared_execution_fields");
            clearedExecutionFields = clearedExecutionFields == null ? null : List.copyOf(clearedExecutionFields);
            java.util.Objects.requireNonNull(nonMigratedRequirementFields, "non_migrated_requirement_fields");
            nonMigratedRequirementFields = nonMigratedRequirementFields == null ? null : List.copyOf(nonMigratedRequirementFields);
            java.util.Objects.requireNonNull(appliedDefaults, "applied_defaults");
            appliedDefaults = appliedDefaults == null ? null : List.copyOf(appliedDefaults);
        }
    }
    /** PlanFocusReceipt 的已知字段；数组在构造时复制以固定快照。 */
    public record PlanFocusReceipt(
        @JsonProperty("type") String type,
        @JsonProperty("focus") SessionPlanFocus focus
    ) implements GenerationPlanToolResult {
        public PlanFocusReceipt {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_focus_receipt".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(focus, "focus");
        }
    }
    /** PlanTaskSubmissionReceipt 的已知字段；数组在构造时复制以固定快照。 */
    public record PlanTaskSubmissionReceipt(
        @JsonProperty("type") String type,
        @JsonProperty("plan_id") UUID planId,
        @JsonProperty("frozen_plan_revision") Long frozenPlanRevision,
        @JsonProperty("accepted") Boolean accepted,
        @JsonProperty("requested_file_count") Integer requestedFileCount,
        @JsonProperty("tasks") List<SubmittedTask> tasks,
        @JsonProperty("focus") SessionPlanFocus focus
    ) implements GenerationPlanToolResult {
        public PlanTaskSubmissionReceipt {
            java.util.Objects.requireNonNull(type, "type");
            if (!"plan_task_submission_receipt".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(planId, "plan_id");
            java.util.Objects.requireNonNull(frozenPlanRevision, "frozen_plan_revision");
            java.util.Objects.requireNonNull(accepted, "accepted");
            if (!accepted.equals(true)) throw new IllegalArgumentException("Invalid accepted");
            java.util.Objects.requireNonNull(requestedFileCount, "requested_file_count");
            java.util.Objects.requireNonNull(tasks, "tasks");
            tasks = tasks == null ? null : List.copyOf(tasks);
            java.util.Objects.requireNonNull(focus, "focus");
        }
    }
    /** PlanToolErrorCode 的固定协议值。 */
    public enum PlanToolErrorCode { INVALID_ARGUMENT, RESOURCE_NOT_FOUND, PLAN_FROZEN, PLAN_REVISION_CONFLICT, FOCUS_REVISION_CONFLICT, PLAN_NOT_FOCUSED, TARGET_NOT_AVAILABLE, CAPABILITIES_CHANGED, PLAN_NOT_SUBMITTABLE, OPERATION_IDENTITY_CONFLICT, CONCURRENT_MODIFICATION, DEPENDENT_TOOL_BATCH }
    /** PlanToolRejection 的已知字段；数组在构造时复制以固定快照。 */
    public record PlanToolRejection(
        @JsonProperty("type") String type,
        @JsonProperty("code") PlanToolErrorCode code,
        @JsonProperty("message") String message,
        @JsonProperty("current_plan_revision") Long currentPlanRevision,
        @JsonProperty("current_focus_revision") Long currentFocusRevision,
        @JsonProperty("issues") List<ValidationIssue> issues,
        @JsonProperty("business_execution") String businessExecution
    ) implements GenerationPlanToolResult {
        public PlanToolRejection {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_tool_rejection".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(code, "code");
            java.util.Objects.requireNonNull(message, "message");
            java.util.Objects.requireNonNull(issues, "issues");
            issues = issues == null ? null : List.copyOf(issues);
            java.util.Objects.requireNonNull(businessExecution, "business_execution");
            if (!"NOT_PERFORMED".equals(businessExecution)) throw new IllegalArgumentException("Invalid business_execution");
        }
    }
    /** GenerationPlanToolResult 的封闭变体，禁止未知业务结构。 */
    public sealed interface GenerationPlanToolResult permits ModelCatalogSnapshot, GenerationPlanReadResult, PlanMutationReceipt, PlanFocusReceipt, PlanTaskSubmissionReceipt, PlanToolRejection {
        String type();
    }
    /** JavaPlanToolOperationReceipt 的已知字段；数组在构造时复制以固定快照。 */
    public record JavaPlanToolOperationReceipt(
        @JsonProperty("type") String type,
        @JsonProperty("schema_version") Integer schemaVersion,
        @JsonProperty("operation_id") UUID operationId,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("tool_name") GenerationPlanToolName toolName,
        @JsonProperty("request_hash") String requestHash,
        @JsonProperty("result") GenerationPlanToolResult result
    ) {
        public JavaPlanToolOperationReceipt {
            java.util.Objects.requireNonNull(type, "type");
            if (!"generation_plan_tool_operation_receipt".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(schemaVersion, "schema_version");
            if (!schemaVersion.equals(1)) throw new IllegalArgumentException("Invalid schema_version");
            java.util.Objects.requireNonNull(operationId, "operation_id");
            java.util.Objects.requireNonNull(sessionId, "session_id");
            java.util.Objects.requireNonNull(toolName, "tool_name");
            java.util.Objects.requireNonNull(requestHash, "request_hash");
            java.util.Objects.requireNonNull(result, "result");
        }
    }
    /** VerifiedImageMetadata 的已知字段；数组在构造时复制以固定快照。 */
    public record VerifiedImageMetadata(
        @JsonProperty("type") String type,
        @JsonProperty("asset_id") UUID assetId,
        @JsonProperty("sha256") String sha256,
        @JsonProperty("size_bytes") Long sizeBytes,
        @JsonProperty("detected_mime_type") String detectedMimeType,
        @JsonProperty("dimensions") PixelDimensions dimensions
    ) implements VerifiedMediaMetadata {
        public VerifiedImageMetadata {
            java.util.Objects.requireNonNull(type, "type");
            if (!"verified_image_metadata".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(assetId, "asset_id");
            java.util.Objects.requireNonNull(sha256, "sha256");
            java.util.Objects.requireNonNull(sizeBytes, "size_bytes");
            java.util.Objects.requireNonNull(detectedMimeType, "detected_mime_type");
            java.util.Objects.requireNonNull(dimensions, "dimensions");
        }
    }
    /** VerifiedVideoMetadata 的已知字段；数组在构造时复制以固定快照。 */
    public record VerifiedVideoMetadata(
        @JsonProperty("type") String type,
        @JsonProperty("asset_id") UUID assetId,
        @JsonProperty("sha256") String sha256,
        @JsonProperty("size_bytes") Long sizeBytes,
        @JsonProperty("detected_mime_type") String detectedMimeType,
        @JsonProperty("dimensions") PixelDimensions dimensions,
        @JsonProperty("duration_milliseconds") Long durationMilliseconds,
        @JsonProperty("has_audio") Boolean hasAudio
    ) implements VerifiedMediaMetadata {
        public VerifiedVideoMetadata {
            java.util.Objects.requireNonNull(type, "type");
            if (!"verified_video_metadata".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(assetId, "asset_id");
            java.util.Objects.requireNonNull(sha256, "sha256");
            java.util.Objects.requireNonNull(sizeBytes, "size_bytes");
            java.util.Objects.requireNonNull(detectedMimeType, "detected_mime_type");
            java.util.Objects.requireNonNull(dimensions, "dimensions");
            java.util.Objects.requireNonNull(durationMilliseconds, "duration_milliseconds");
            java.util.Objects.requireNonNull(hasAudio, "has_audio");
        }
    }
    /** VerifiedAudioMetadata 的已知字段；数组在构造时复制以固定快照。 */
    public record VerifiedAudioMetadata(
        @JsonProperty("type") String type,
        @JsonProperty("asset_id") UUID assetId,
        @JsonProperty("sha256") String sha256,
        @JsonProperty("size_bytes") Long sizeBytes,
        @JsonProperty("detected_mime_type") String detectedMimeType,
        @JsonProperty("duration_milliseconds") Long durationMilliseconds
    ) implements VerifiedMediaMetadata {
        public VerifiedAudioMetadata {
            java.util.Objects.requireNonNull(type, "type");
            if (!"verified_audio_metadata".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(assetId, "asset_id");
            java.util.Objects.requireNonNull(sha256, "sha256");
            java.util.Objects.requireNonNull(sizeBytes, "size_bytes");
            java.util.Objects.requireNonNull(detectedMimeType, "detected_mime_type");
            java.util.Objects.requireNonNull(durationMilliseconds, "duration_milliseconds");
        }
    }
    /** VerifiedMediaMetadata 的封闭变体，禁止未知业务结构。 */
    public sealed interface VerifiedMediaMetadata permits VerifiedImageMetadata, VerifiedVideoMetadata, VerifiedAudioMetadata {
        String type();
        UUID assetId();
        String sha256();
        Long sizeBytes();
        String detectedMimeType();
    }
    /** ReferenceAsset 的已知字段；数组在构造时复制以固定快照。 */
    public record ReferenceAsset(
        @JsonProperty("type") String type,
        @JsonProperty("asset_id") UUID assetId
    ) {
        public ReferenceAsset {
            java.util.Objects.requireNonNull(type, "type");
            if (!"reference_asset".equals(type)) throw new IllegalArgumentException("Invalid type");
            java.util.Objects.requireNonNull(assetId, "asset_id");
        }
    }
    /** ReferenceMediaType 的固定协议值。 */
    public enum ReferenceMediaType { IMAGE, VIDEO, AUDIO }
    /** 引用图像的闭区间比例约束，使用整数比避免浮点边界误差。 */
    public record AspectRatioRange(
        @JsonProperty("minimum") AspectRatio minimum,
        @JsonProperty("maximum") AspectRatio maximum
    ) {
        public AspectRatioRange {
            java.util.Objects.requireNonNull(minimum, "minimum");
            java.util.Objects.requireNonNull(maximum, "maximum");
        }
    }
    /** ReferenceMediaLimit 的已知字段；数组在构造时复制以固定快照。 */
    public record ReferenceMediaLimit(
        @JsonProperty("media_type") ReferenceMediaType mediaType,
        @JsonProperty("minimum_count") Integer minimumCount,
        @JsonProperty("maximum_count") Integer maximumCount,
        @JsonProperty("mime_types") List<String> mimeTypes,
        @JsonProperty("max_asset_bytes") Long maxAssetBytes,
        @JsonProperty("dimensions") PixelBounds dimensions,
        @JsonProperty("max_duration_milliseconds") Long maxDurationMilliseconds,
        @JsonProperty("aspect_ratio_range") @JsonInclude(JsonInclude.Include.NON_NULL) AspectRatioRange aspectRatioRange
    ) {
        public ReferenceMediaLimit {
            java.util.Objects.requireNonNull(mediaType, "media_type");
            java.util.Objects.requireNonNull(minimumCount, "minimum_count");
            java.util.Objects.requireNonNull(maximumCount, "maximum_count");
            java.util.Objects.requireNonNull(mimeTypes, "mime_types");
            mimeTypes = mimeTypes == null ? null : List.copyOf(mimeTypes);
            java.util.Objects.requireNonNull(maxAssetBytes, "max_asset_bytes");
        }
        /** 旧目录构造器不添加比例限制，旧 JSON 缺少新字段时也保持原有语义。 */
        public ReferenceMediaLimit(ReferenceMediaType mediaType, Integer minimumCount, Integer maximumCount,
                List<String> mimeTypes, Long maxAssetBytes, PixelBounds dimensions, Long maxDurationMilliseconds) {
            this(mediaType, minimumCount, maximumCount, mimeTypes, maxAssetBytes, dimensions, maxDurationMilliseconds, null);
        }
    }
    /** SetRequestedFileCount 的已知字段；数组在构造时复制以固定快照。 */
    public record SetRequestedFileCount(
        @JsonProperty("op") String op,
        @JsonProperty("value") Integer value
    ) implements RequestedFileCountChange {
        public SetRequestedFileCount {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
        }
    }
    /** RequestedFileCountChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface RequestedFileCountChange permits KeepChange, SetRequestedFileCount, ClearChange {
        String op();
    }
    /** SetReferences 的已知字段；数组在构造时复制以固定快照。 */
    public record SetReferences(
        @JsonProperty("op") String op,
        @JsonProperty("value") List<ReferenceAsset> value
    ) implements ReferencesChange {
        public SetReferences {
            java.util.Objects.requireNonNull(op, "op");
            if (!"SET".equals(op)) throw new IllegalArgumentException("Invalid op");
            java.util.Objects.requireNonNull(value, "value");
            value = value == null ? null : List.copyOf(value);
        }
    }
    /** ReferencesChange 的封闭变体，禁止未知业务结构。 */
    public sealed interface ReferencesChange permits KeepChange, SetReferences, ClearChange {
        String op();
    }
    /** GenerationSubmissionPolicy 的已知字段；数组在构造时复制以固定快照。 */
    public record GenerationSubmissionPolicy(
        @JsonProperty("maximum_tasks_per_submission") Integer maximumTasksPerSubmission
    ) {
        public GenerationSubmissionPolicy {
            java.util.Objects.requireNonNull(maximumTasksPerSubmission, "maximum_tasks_per_submission");
        }
    }
    /** SubmittedTask 的已知字段；数组在构造时复制以固定快照。 */
    public record SubmittedTask(
        @JsonProperty("index") Integer index,
        @JsonProperty("task_id") UUID taskId,
        @JsonProperty("task_operation_id") UUID taskOperationId
    ) {
        public SubmittedTask {
            java.util.Objects.requireNonNull(index, "index");
            java.util.Objects.requireNonNull(taskId, "task_id");
            java.util.Objects.requireNonNull(taskOperationId, "task_operation_id");
        }
    }
    /** ProviderBinding 的已知字段；数组在构造时复制以固定快照。 */
    public record ProviderBinding(
        @JsonProperty("provider_id") String providerId,
        @JsonProperty("provider_model_id") String providerModelId,
        @JsonProperty("adapter_id") String adapterId
    ) {
        public ProviderBinding {
            requireNonblank(providerId, "provider_id");
            requireNonblank(providerModelId, "provider_model_id");
            requireNonblank(adapterId, "adapter_id");
        }

        /** 路由标识必须包含非空白字符；统一 Unicode 空白语义，但不修剪或改写实际标识。 */
        private static void requireNonblank(String value, String field) {
            java.util.Objects.requireNonNull(value, field);
            boolean nonblank = value.codePoints().anyMatch(character ->
                !Character.isWhitespace(character) && !Character.isSpaceChar(character) && character != 0x85);
            if (!nonblank) throw new IllegalArgumentException("Blank " + field);
        }
    }
    /** 显式声明的 ParameterOriginSource 值域。 */
    public enum ParameterOriginSource { EXPLICIT, MODEL_DEFAULT }
    /** 显式声明的 CapabilityEvidenceSourceKind 值域。 */
    public enum CapabilityEvidenceSourceKind { OFFICIAL_DOCUMENTATION, CONTRACT_VERIFICATION, PRODUCT_POLICY }
    /** 显式声明的 UnavailableImageProfileStatus 值域。 */
    public enum UnavailableImageProfileStatus { UNKNOWN, UNSUPPORTED }
    /** 显式声明的 UnavailableVideoProfileStatus 值域。 */
    public enum UnavailableVideoProfileStatus { UNKNOWN, UNSUPPORTED }
    /** 显式声明的 PlanMutationReceiptAction 值域。 */
    public enum PlanMutationReceiptAction { CREATED, UPDATED, MODEL_CHANGED, MEDIA_PLAN_CREATED, CLONED }
    /** 域计算返回的完整配置及可解释变化，不含持久化或外部调用。 */
    public record ConfigurationChange(PlanConfiguration configuration, List<ParameterKey> clearedFields,
                                      List<IssueField> nonMigratedRequirementFields, List<AppliedDefault> appliedDefaults) {
        public ConfigurationChange {
            clearedFields = List.copyOf(clearedFields);
            nonMigratedRequirementFields = List.copyOf(nonMigratedRequirementFields);
            appliedDefaults = List.copyOf(appliedDefaults);
        }
    }
}
