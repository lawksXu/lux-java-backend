package com.lux.backend.generation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.lux.backend.generation.GenerationPlanContract.*;
import static com.lux.backend.generation.GenerationPlanDomain.*;

class GenerationPlanDomainTests {
    private static final ProviderBinding BINDING = new ProviderBinding("unregistered-test-provider", "unregistered-test-model", "unregistered-test-v1");
    private static final RatioSize SQUARE = new RatioSize("ratio_size", new AspectRatio(1, 1), "test-resolution");
    private static final RatioSize WIDE = new RatioSize("ratio_size", new AspectRatio(2, 1), "test-resolution");
    private static final KeepChange KEEP = new KeepChange("KEEP");
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private final ModelCatalog catalog = catalog();
    private final GenerationPlanDomain domain = new GenerationPlanDomain(catalog);
    private final AdmissionFacts ready = new AdmissionFacts(Map.of(), Set.of(), Set.of(BINDING), Set.of(BINDING));

    @Test void productionCatalogDisabledAndCountPolicyIndependent() {
        assertEquals(4, new ModelCatalog().snapshot().models().size());
        assertTrue(new ModelCatalog().snapshot().models().stream().noneMatch(ModelDefinition::enabled));
        assertEquals(32, new ModelCatalog().snapshot().submissionPolicy().maximumTasksPerSubmission());
        ImagePlanConfiguration unselected = (ImagePlanConfiguration) domain.create(create(null, requirements(null, null, 33, null, null), parameters(null, null, List.of(), null))).configuration();
        PlanValidation validation = domain.validation(unselected);
        assertEquals(IssueCode.MODEL_REQUIRED, validation.issues().get(0).code());
        assertTrue(validation.issues().stream().anyMatch(i -> i.code() == IssueCode.REQUESTED_FILE_COUNT_UNSUPPORTED));
        assertEquals(33, unselected.intent().requirements().requestedFileCount());
    }

    @Test void exactRequirementsNormalizeRatioAndDefaultsOnlyFillUnconstrainedValues() {
        ConfigurationChange change = domain.create(create(choice("test-image"), requirements("literal prompt", new RatioSize("ratio_size", new AspectRatio(2, 2), null), 3, List.of(), null), parameters(null, null, List.of(), null)), ready);
        ImagePlanConfiguration result = (ImagePlanConfiguration) change.configuration();
        assertEquals(new AspectRatio(1, 1), ((RatioSize) result.parameters().size()).aspectRatio());
        assertEquals(List.of(ParameterKey.RESOLUTION_ID, ParameterKey.OUTPUT_FORMAT), change.appliedDefaults().stream().map(AppliedDefault::field).toList());
        assertTrue(domain.validation(result, ready).canSubmit());
        assertEquals(3, domain.requestedFileCount(result));
        ImageGenerationRequest request = (ImageGenerationRequest) domain.freezeRequest(plan(result), ready);
        assertEquals(1, request.resultExpectations().fileCount());
        assertEquals(BINDING, request.providerBinding());
    }

    @Test void newRequirementReplacesContradictingOldModelDefaultButRetainsExplicitConflict() {
        ImagePlanConfiguration draft = (ImagePlanConfiguration) domain.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", null, List.of(), null)), ready).configuration();
        ImagePlanPatch setJpegRequirement = patchRequirements(new SetImageOutputFormat("SET", ImageOutputFormat.JPEG));
        ConfigurationChange change = domain.update(draft, setJpegRequirement, ready);
        assertEquals(ImageOutputFormat.JPEG, ((ImagePlanConfiguration) change.configuration()).parameters().outputFormat());
        assertTrue(domain.validation(change.configuration(), ready).canSubmit());
        ImagePlanConfiguration explicit = (ImagePlanConfiguration) domain.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", SQUARE, List.of(), ImageOutputFormat.PNG)), ready).configuration();
        ConfigurationChange retained = domain.update(explicit, setJpegRequirement, ready);
        assertEquals(ImageOutputFormat.PNG, ((ImagePlanConfiguration) retained.configuration()).parameters().outputFormat());
        assertTrue(domain.validation(retained.configuration(), ready).issues().stream().anyMatch(i -> i.code() == IssueCode.REQUIREMENT_UNSATISFIED));
    }

    @Test void unsupportedExplicitAssignmentRejectsWhileUnsupportedRequirementPersists() {
        assertThrows(DomainException.class, () -> domain.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", new PixelSize("pixel_size", new PixelDimensions(123, 456)), List.of(), null)), ready));
        ImagePlanConfiguration result = (ImagePlanConfiguration) domain.create(create(choice("test-image"), requirements(null, new RatioSize("ratio_size", new AspectRatio(9, 16), null), null, null, null), parameters("prompt", null, List.of(), null)), ready).configuration();
        assertEquals(new AspectRatio(9, 16), ((RatioSize) result.intent().requirements().size()).aspectRatio());
        assertTrue(domain.validation(result, ready).issues().stream().anyMatch(i -> i.code() == IssueCode.REQUIREMENT_UNSATISFIED));
    }

    @Test void pixelRequirementMatchesConcretePresetDimensions() {
        ImagePlanConfiguration result = (ImagePlanConfiguration) domain.create(create(choice("test-image"), requirements(null, new PixelSize("pixel_size", new PixelDimensions(64, 64)), null, null, null), parameters("prompt", SQUARE, List.of(), ImageOutputFormat.PNG)), ready).configuration();
        assertTrue(domain.validation(result, ready).canSubmit());
    }

    @Test void modelChangePreservesRequirementsClearsIncompatibleFieldsAndRetainsCompatibleOrigins() {
        ImagePlanConfiguration source = (ImagePlanConfiguration) domain.create(create(choice("test-image"), requirements(null, SQUARE, 2, null, null), parameters("prompt", SQUARE, List.of(), null)), ready).configuration();
        ConfigurationChange change = domain.retarget(source, new ImageTarget("IMAGE", choice("test-wide")), ready);
        ImagePlanConfiguration result = (ImagePlanConfiguration) change.configuration();
        assertEquals(source.intent(), result.intent());
        assertTrue(change.clearedFields().contains(ParameterKey.ASPECT_RATIO));
        assertEquals("prompt", result.parameters().prompt());
        assertEquals(source.parameterOrigins().stream().filter(p -> p.field() == ParameterKey.OUTPUT_FORMAT).findFirst().orElseThrow(), result.parameterOrigins().stream().filter(p -> p.field() == ParameterKey.OUTPUT_FORMAT).findFirst().orElseThrow());
        assertTrue(domain.validation(result, ready).issues().stream().anyMatch(i -> i.code() == IssueCode.REQUIREMENT_UNSATISFIED));
    }

    @Test void crossMediaCopiesOnlyDescriptionRatioAndExplicitCountAndCloneDoesNotReapplyDefaults() {
        ImagePlanConfiguration source = (ImagePlanConfiguration) domain.create(create(choice("test-image"), requirements("literal", SQUARE, 2, List.of(), ImageOutputFormat.PNG), parameters("literal", SQUARE, List.of(), ImageOutputFormat.PNG)), ready).configuration();
        ConfigurationChange changed = domain.retarget(source, new VideoTarget("VIDEO", null), ready);
        VideoPlanConfiguration result = (VideoPlanConfiguration) changed.configuration();
        assertEquals(source.intent().description(), result.intent().description());
        assertEquals(2, result.intent().requirements().requestedFileCount());
        assertNull(result.intent().requirements().prompt());
        assertNull(((RatioSize) result.intent().requirements().size()).resolutionId());
        assertNull(result.parameters().prompt());
        assertTrue(changed.nonMigratedRequirementFields().containsAll(List.of(IssueField.PROMPT, IssueField.RESOLUTION_ID, IssueField.REFERENCES, IssueField.OUTPUT_FORMAT)));
        assertSame(source, domain.cloneConfiguration(source).configuration());
        assertTrue(domain.cloneConfiguration(source).appliedDefaults().isEmpty());
    }

    @Test void frozenReusePreservesExactRequestAndChangedCapabilityCannotSubstituteBinding() {
        PlanConfiguration config = domain.create(create(choice("test-image"), requirements(null, null, 2, null, null), parameters("prompt", null, List.of(), null)), ready).configuration();
        NormalizedGenerationRequest request = domain.freezeRequest(plan(config), ready);
        GenerationPlan frozen = new GenerationPlan("generation_plan", 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2L, PlanState.FROZEN, config, request, NOW, NOW, NOW);
        assertSame(request, domain.freezeRequest(frozen, ready));
        ImageModelDefinition original = (ImageModelDefinition) catalog.snapshot().models().get(0);
        ImageModelDefinition revised = new ImageModelDefinition(original.type(), original.modelId(), original.displayName(), 2L, true, new ProviderBinding("other", "other", "other-v1"), original.profiles());
        GenerationPlanDomain updated = new GenerationPlanDomain(new ModelCatalog(new ModelCatalogSnapshot("model_catalog_snapshot", 2L, new GenerationSubmissionPolicy(32), List.of(revised))));
        assertTrue(updated.validation(frozen, ready).issues().stream().anyMatch(i -> i.code() == IssueCode.CAPABILITY_REVISION_UNAVAILABLE));
        assertEquals(BINDING, frozen.frozenRequest().providerBinding());
    }

    @Test void absentExecutionOrResultBoundaryAlwaysBlocksAndStaleChoiceRejects() {
        PlanConfiguration config = domain.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", null, List.of(), null)), ready).configuration();
        PlanValidation validation = domain.validation(config);
        assertTrue(validation.issues().stream().anyMatch(i -> i.code() == IssueCode.EXECUTION_ADAPTER_UNAVAILABLE));
        assertTrue(validation.issues().stream().anyMatch(i -> i.code() == IssueCode.RESULT_VERIFIER_UNAVAILABLE));
        DomainException error = assertThrows(DomainException.class, () -> domain.create(create(new ModelChoice("test-image", "test-profile", 2L), requirements(null, null, null, null, null), parameters("prompt", null, List.of(), null)), ready));
        assertEquals(PlanToolErrorCode.CAPABILITIES_CHANGED, error.code());
    }

    @Test void falseVideoRequirementOverridesDefaultAndCombinationIsEnforced() {
        VideoRequirements required = new VideoRequirements("video_requirements", "prompt", null, null, null, null, 10, false, null);
        VideoPlanConfiguration result = (VideoPlanConfiguration) domain.create(new CreateVideoPlanArguments(new VideoTarget("VIDEO", choice("test-video")), new VideoIntent("video_intent", "description", required), new VideoParameters("video_parameters", null, null, List.of(), null, null, null), 0L), ready).configuration();
        assertFalse(result.parameters().generateAudio());
        assertTrue(domain.validation(result, ready).canSubmit());
        assertFalse(((VideoGenerationRequest) domain.freezeRequest(plan(result), ready)).resultExpectations().expectedAudio());
        assertThrows(DomainException.class, () -> domain.create(new CreateVideoPlanArguments(new VideoTarget("VIDEO", choice("test-video")), new VideoIntent("video_intent", "description", required), new VideoParameters("video_parameters", "prompt", WIDE, List.of(), VideoOutputFormat.MP4, 10, false), 0L), ready));
    }

    @Test void productPolicyAloneAndUncoveredVideoPresetCannotRegisterVerifiedSupport() {
        CapabilityEvidence policy = new CapabilityEvidence(CapabilityEvidenceSourceKind.PRODUCT_POLICY, "test", NOW, "synthetic policy");
        VerifiedImageProfile original = imageProfile(SQUARE);
        VerifiedImageProfile invalid = new VerifiedImageProfile(original.type(), original.profileId(), original.generationMode(), original.promptRules(), original.sizeRules(), original.referenceRules(), original.outputFormats(), original.defaults(), List.of(policy));
        assertThrows(IllegalArgumentException.class, () -> new ModelCatalog(new ModelCatalogSnapshot("model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32), List.of(new ImageModelDefinition("image_model_definition", "test-image", "test", 1L, true, BINDING, List.of(invalid))))));
        VerifiedVideoProfile v = videoProfile();
        VerifiedVideoProfile uncovered = new VerifiedVideoProfile(v.type(), v.profileId(), v.generationMode(), v.promptRules(), v.sizeRules(), List.of(v.outputOptions().get(0)), v.referenceRules(), v.outputFormats(), v.generateAudioValues(), v.durationToleranceMilliseconds(), v.defaults(), v.evidence());
        assertThrows(IllegalArgumentException.class, () -> new ModelCatalog(new ModelCatalogSnapshot("model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32), List.of(new VideoModelDefinition("video_model_definition", "test-video", "test", 1L, true, BINDING, List.of(uncovered))))));
    }


    @Test void trustedReferenceFactsAndOrderedRequirementsAreRequiredAndDuplicatesReject() {
        UUID asset = UUID.randomUUID(); ReferenceAsset reference = new ReferenceAsset("reference_asset", asset);
        ReferenceRules rules = new ReferenceRules(0, 2, List.of(new ReferenceMediaLimit(ReferenceMediaType.IMAGE, 0, 2, List.of("image/png"), 1000L, new PixelBounds(1, 100, 1, 100, 10000L), null)), null, null, null);
        VerifiedImageProfile original = imageProfile(SQUARE);
        VerifiedImageProfile profile = new VerifiedImageProfile(original.type(), original.profileId(), original.generationMode(), original.promptRules(), original.sizeRules(), rules, original.outputFormats(), original.defaults(), original.evidence());
        GenerationPlanDomain references = new GenerationPlanDomain(new ModelCatalog(new ModelCatalogSnapshot("model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32), List.of(new ImageModelDefinition("image_model_definition", "test-image", "synthetic refs", 1L, true, BINDING, List.of(profile))))));
        PlanConfiguration config = references.create(create(choice("test-image"), requirements(null, null, null, List.of(reference), null), parameters("prompt", null, List.of(), null)), ready).configuration();
        assertEquals(List.of(reference), ((ImagePlanConfiguration) config).parameters().references());
        assertTrue(references.validation(config, ready).issues().stream().anyMatch(i -> i.code() == IssueCode.REFERENCE_METADATA_UNAVAILABLE && i.referenceIndex() == 0));
        VerifiedImageMetadata metadata = new VerifiedImageMetadata("verified_image_metadata", asset, "0".repeat(64), 10L, "image/png", new PixelDimensions(64, 64));
        AdmissionFacts inspected = new AdmissionFacts(Map.of(asset, metadata), Set.of(), Set.of(BINDING), Set.of(BINDING));
        assertTrue(references.validation(config, inspected).canSubmit());
        AdmissionFacts unavailable = new AdmissionFacts(inspected.metadata(), Set.of(asset), inspected.executionAdapters(), inspected.resultVerifiers());
        assertTrue(references.validation(config, unavailable).issues().stream().anyMatch(i -> i.code() == IssueCode.REFERENCE_UNAVAILABLE));
        assertThrows(IllegalArgumentException.class, () -> references.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", null, List.of(reference, reference), null)), inspected));
        assertThrows(DomainException.class, () -> references.create(create(choice("test-image"), requirements(null, null, null, List.of(), null), parameters("prompt", null, List.of(reference), null)), new AdmissionFacts(Map.of(asset, new VerifiedAudioMetadata("verified_audio_metadata", asset, "0".repeat(64), 10L, "audio/wav", 1000L)), Set.of(), Set.of(BINDING), Set.of(BINDING))));
    }

    @Test void combinedReferenceDurationRejectsExplicitUpdateAndModelMigrationClearsDurationOnly() {
        UUID asset = UUID.randomUUID(); ReferenceAsset reference = new ReferenceAsset("reference_asset", asset);
        VerifiedVideoMetadata metadata = new VerifiedVideoMetadata("verified_video_metadata", asset, "0".repeat(64), 10L, "video/mp4", new PixelDimensions(64, 64), 1000L, false);
        AdmissionFacts facts = new AdmissionFacts(Map.of(asset, metadata), Set.of(), Set.of(BINDING), Set.of(BINDING));
        GenerationPlanDomain refs = new GenerationPlanDomain(new ModelCatalog(new ModelCatalogSnapshot("model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32), List.of(videoWithBudget("test-video", null), videoWithBudget("test-limited-video", 7000L)))));
        VideoRequirements requirements = new VideoRequirements("video_requirements", null, null, null, null, null, null, null, null);
        PlanConfiguration source = refs.create(new CreateVideoPlanArguments(new VideoTarget("VIDEO", choice("test-video")), new VideoIntent("video_intent", "description", requirements), new VideoParameters("video_parameters", "prompt", SQUARE, List.of(reference), VideoOutputFormat.MP4, 10, false), 0L), facts).configuration();
        ConfigurationChange changed = refs.retarget(source, new VideoTarget("VIDEO", choice("test-limited-video")), facts);
        assertEquals(List.of(reference), ((VideoPlanConfiguration) changed.configuration()).parameters().references());
        assertTrue(changed.clearedFields().contains(ParameterKey.DURATION_SECONDS));
        VideoPlanPatch duration = new VideoPlanPatch("video_plan_patch", KEEP, new VideoRequirementsPatch(KEEP, KEEP, KEEP, KEEP, KEEP, KEEP, KEEP, KEEP), new VideoParametersPatch(KEEP, KEEP, KEEP, KEEP, new SetDuration("SET", 10), KEEP));
        assertThrows(DomainException.class, () -> refs.update(changed.configuration(), duration, facts));
    }
    private static VideoModelDefinition videoWithBudget(String model, Long maximum) {
        VerifiedVideoProfile original = videoProfile();
        ReferenceRules references = new ReferenceRules(0, 1, List.of(new ReferenceMediaLimit(ReferenceMediaType.VIDEO, 0, 1, List.of("video/mp4"), 1000L, null, 10000L)), null, null, maximum);
        VerifiedVideoProfile profile = new VerifiedVideoProfile(original.type(), original.profileId(), original.generationMode(), original.promptRules(), original.sizeRules(), original.outputOptions(), references, original.outputFormats(), original.generateAudioValues(), original.durationToleranceMilliseconds(), original.defaults(), original.evidence());
        return new VideoModelDefinition("video_model_definition", model, "synthetic budget", 1L, true, BINDING, List.of(profile));
    }


    @Test void clearCandidateReferencesMaterializesRequirementButSetEmptyRequirementEnforcesNoReferences() {
        UUID asset = UUID.randomUUID(); ReferenceAsset ref = new ReferenceAsset("reference_asset", asset);
        PlanConfiguration draft = domain.create(create(null, requirements("literal", null, null, List.of(ref), null), parameters(null, null, List.of(), null))).configuration();
        ImagePlanPatch clear = new ImagePlanPatch("image_plan_patch", KEEP, new ImageRequirementsPatch(KEEP, KEEP, KEEP, KEEP, KEEP, KEEP), new ImageParametersPatch(KEEP, KEEP, new ClearChange("CLEAR"), KEEP));
        PlanConfiguration materialized = domain.update(draft, clear).configuration();
        assertEquals(List.of(ref), ((ImagePlanConfiguration) materialized).parameters().references());
        ImagePlanPatch noRefs = new ImagePlanPatch("image_plan_patch", KEEP, new ImageRequirementsPatch(KEEP, KEEP, KEEP, new SetReferences("SET", List.of()), KEEP, KEEP), new ImageParametersPatch(KEEP, KEEP, new ClearChange("CLEAR"), KEEP));
        ImagePlanConfiguration none = (ImagePlanConfiguration) domain.update(materialized, noRefs).configuration();
        assertEquals(List.of(), none.intent().requirements().references());
        assertEquals(List.of(), none.parameters().references());
        assertFalse(none.parameterOrigins().stream().anyMatch(p -> p.field() == ParameterKey.REFERENCES));
    }

    @Test void forgedPromptDefaultOriginCannotEstablishAValidPlan() {
        ImagePlanConfiguration valid = (ImagePlanConfiguration) domain.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", null, List.of(), null)), ready).configuration();
        List<ParameterOrigin> forged = valid.parameterOrigins().stream().map(p -> p.field() == ParameterKey.PROMPT ? new ParameterOrigin(p.field(), ParameterOriginSource.MODEL_DEFAULT, valid.modelSelection()) : p).toList();
        ImagePlanConfiguration invalid = new ImagePlanConfiguration(valid.type(), valid.modelSelection(), valid.intent(), valid.parameters(), forged);
        assertThrows(IllegalArgumentException.class, () -> domain.validation(invalid, ready));
    }

    @Test void noOpKeepUpdateDoesNotChangeConfigurationOrReapplyDefaults() {
        PlanConfiguration source = domain.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", null, List.of(), null)), ready).configuration();
        ImagePlanPatch noOp = new ImagePlanPatch("image_plan_patch", KEEP, new ImageRequirementsPatch(KEEP, KEEP, KEEP, KEEP, KEEP, KEEP), new ImageParametersPatch(KEEP, KEEP, KEEP, KEEP));
        assertEquals(source, domain.update(source, noOp, ready).configuration());
        assertTrue(domain.update(source, noOp, ready).appliedDefaults().isEmpty());
    }


    @Test void clearingMandatoryReferencesSavesMissingSelectionAsBlocker() {
        UUID asset = UUID.randomUUID(); ReferenceAsset ref = new ReferenceAsset("reference_asset", asset);
        VerifiedImageProfile original = imageProfile(SQUARE);
        ReferenceRules rules = new ReferenceRules(1, 1, List.of(new ReferenceMediaLimit(ReferenceMediaType.IMAGE, 1, 1, List.of("image/png"), 1000L, null, null)), null, null, null);
        VerifiedImageProfile profile = new VerifiedImageProfile(original.type(), original.profileId(), original.generationMode(), original.promptRules(), original.sizeRules(), rules, original.outputFormats(), original.defaults(), original.evidence());
        GenerationPlanDomain refs = new GenerationPlanDomain(new ModelCatalog(new ModelCatalogSnapshot("model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32), List.of(new ImageModelDefinition("image_model_definition", "test-image", "synthetic refs", 1L, true, BINDING, List.of(profile))))));
        AdmissionFacts facts = new AdmissionFacts(Map.of(asset, new VerifiedImageMetadata("verified_image_metadata", asset, "0".repeat(64), 10L, "image/png", new PixelDimensions(64, 64))), Set.of(), Set.of(BINDING), Set.of(BINDING));
        PlanConfiguration source = refs.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", null, List.of(ref), null)), facts).configuration();
        ImagePlanPatch clear = new ImagePlanPatch("image_plan_patch", KEEP, new ImageRequirementsPatch(KEEP, KEEP, KEEP, KEEP, KEEP, KEEP), new ImageParametersPatch(KEEP, KEEP, new ClearChange("CLEAR"), KEEP));
        PlanConfiguration saved = assertDoesNotThrow(() -> refs.update(source, clear, facts)).configuration();
        assertEquals(List.of(), ((ImagePlanConfiguration) saved).parameters().references());
        assertFalse(refs.validation(saved, facts).canSubmit());
    }


    @Test void ratioRequirementsRemoveOnlyConflictingPixelModelDefaultsWithoutInventingPresetLabels() {
        GenerationPlanDomain pixels = pixelDefaultDomain();
        ImagePlanConfiguration source = (ImagePlanConfiguration) pixels.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", null, List.of(), null)), ready).configuration();
        assertEquals(new PixelSize("pixel_size", new PixelDimensions(64, 64)), source.parameters().size());
        ConfigurationChange full = pixels.update(source, patchSizeRequirement(new RatioSize("ratio_size", new AspectRatio(16, 9), "test-resolution")), ready);
        ImagePlanConfiguration fullResult = (ImagePlanConfiguration) full.configuration();
        assertEquals(new RatioSize("ratio_size", new AspectRatio(16, 9), "test-resolution"), fullResult.parameters().size());
        assertFalse(fullResult.parameterOrigins().stream().anyMatch(p -> p.field() == ParameterKey.PIXEL_DIMENSIONS));
        assertEquals(ParameterOriginSource.EXPLICIT, fullResult.parameterOrigins().stream().filter(p -> p.field() == ParameterKey.ASPECT_RATIO).findFirst().orElseThrow().source());
        assertTrue(full.appliedDefaults().isEmpty());
        assertTrue(pixels.validation(fullResult, ready).canSubmit());
        ImagePlanConfiguration ratioOnly = (ImagePlanConfiguration) pixels.update(source, patchSizeRequirement(new RatioSize("ratio_size", new AspectRatio(16, 9), null)), ready).configuration();
        assertEquals(new RatioSize("ratio_size", new AspectRatio(16, 9), null), ratioOnly.parameters().size());
        assertFalse(pixels.validation(ratioOnly, ready).canSubmit());
        ImagePlanConfiguration resolutionOnly = (ImagePlanConfiguration) pixels.update(source, patchSizeRequirement(new RatioSize("ratio_size", null, "test-resolution")), ready).configuration();
        assertEquals(new RatioSize("ratio_size", null, "test-resolution"), resolutionOnly.parameters().size());
        assertFalse(pixels.validation(resolutionOnly, ready).canSubmit());
        ConfigurationChange compatibleDefault = pixels.update(source, patchSizeRequirement(new RatioSize("ratio_size", new AspectRatio(2, 2), null)), ready);
        assertEquals(source.parameters().size(), ((ImagePlanConfiguration) compatibleDefault.configuration()).parameters().size());
        assertEquals(source.parameterOrigins(), compatibleDefault.configuration().parameterOrigins());
        assertTrue(pixels.validation(compatibleDefault.configuration(), ready).canSubmit());
    }

    @Test void ratioRequirementsPreserveExplicitCustomPixelsAndTheirOriginsIncludingCompatibleRatio() {
        GenerationPlanDomain pixels = pixelDefaultDomain();
        ImagePlanConfiguration explicit = (ImagePlanConfiguration) pixels.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", new PixelSize("pixel_size", new PixelDimensions(128, 72)), List.of(), ImageOutputFormat.PNG)), ready).configuration();
        ConfigurationChange compatible = pixels.update(explicit, patchSizeRequirement(new RatioSize("ratio_size", new AspectRatio(32, 18), null)), ready);
        assertEquals(explicit.parameters(), ((ImagePlanConfiguration) compatible.configuration()).parameters());
        assertEquals(explicit.parameterOrigins(), compatible.configuration().parameterOrigins());
        assertTrue(pixels.validation(compatible.configuration(), ready).canSubmit());
        ConfigurationChange resolution = pixels.update(explicit, patchSizeRequirement(new RatioSize("ratio_size", null, "test-resolution")), ready);
        assertEquals(explicit.parameters().size(), ((ImagePlanConfiguration) resolution.configuration()).parameters().size());
        assertFalse(pixels.validation(resolution.configuration(), ready).canSubmit());
        ImagePlanConfiguration square = (ImagePlanConfiguration) pixels.create(create(choice("test-image"), requirements(null, null, null, null, null), parameters("prompt", new PixelSize("pixel_size", new PixelDimensions(64, 64)), List.of(), ImageOutputFormat.PNG)), ready).configuration();
        ConfigurationChange conflicting = pixels.update(square, patchSizeRequirement(new RatioSize("ratio_size", new AspectRatio(16, 9), null)), ready);
        assertEquals(square.parameters(), ((ImagePlanConfiguration) conflicting.configuration()).parameters());
        assertEquals(square.parameterOrigins(), conflicting.configuration().parameterOrigins());
        assertTrue(pixels.validation(conflicting.configuration(), ready).issues().stream().anyMatch(i -> i.code() == IssueCode.REQUIREMENT_UNSATISFIED));
    }

    private static GenerationPlanDomain pixelDefaultDomain() {
        VerifiedImageProfile original = imageProfile(SQUARE);
        SizePreset wide = new SizePreset("test-wide", new AspectRatio(16, 9), "test-resolution", new PixelDimensions(128, 72));
        CustomPixelRules custom = new CustomPixelRules(new PixelBounds(1, 256, 1, 256, 65536L), 1, 1, List.of(new AspectRatio(1, 1), new AspectRatio(16, 9)));
        VerifiedImageProfile profile = new VerifiedImageProfile(original.type(), original.profileId(), original.generationMode(), original.promptRules(), new SizeRules(List.of(preset(SQUARE), wide), custom), original.referenceRules(), original.outputFormats(), new ImageDefaults(new PixelSize("pixel_size", new PixelDimensions(64, 64)), ImageOutputFormat.PNG), original.evidence());
        return new GenerationPlanDomain(new ModelCatalog(new ModelCatalogSnapshot("model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32), List.of(new ImageModelDefinition("image_model_definition", "test-image", "synthetic pixels", 1L, true, BINDING, List.of(profile))))));
    }
    private static ImagePlanPatch patchSizeRequirement(DraftOutputSize size) {
        return new ImagePlanPatch("image_plan_patch", KEEP, new ImageRequirementsPatch(KEEP, new SetSize("SET", size), KEEP, KEEP, KEEP, KEEP), new ImageParametersPatch(KEEP, KEEP, KEEP, KEEP));
    }

    private static ModelCatalog catalog() { return new ModelCatalog(new ModelCatalogSnapshot("model_catalog_snapshot", 1L, new GenerationSubmissionPolicy(32), List.of(
        new ImageModelDefinition("image_model_definition", "test-image", "synthetic image", 1L, true, BINDING, List.of(imageProfile(SQUARE))),
        new ImageModelDefinition("image_model_definition", "test-wide", "synthetic wide", 1L, true, BINDING, List.of(imageProfile(WIDE))),
        new VideoModelDefinition("video_model_definition", "test-video", "synthetic video", 1L, true, BINDING, List.of(videoProfile()))))); }
    private static VerifiedImageProfile imageProfile(RatioSize size) { return new VerifiedImageProfile("verified_image_profile", "test-profile", ImageMode.IMAGE, new PromptRules(1000), new SizeRules(List.of(preset(size)), null), noReferences(), List.of(ImageOutputFormat.PNG, ImageOutputFormat.JPEG), new ImageDefaults(size, ImageOutputFormat.PNG), evidence()); }
    private static VerifiedVideoProfile videoProfile() { return new VerifiedVideoProfile("verified_video_profile", "test-profile", VideoMode.VIDEO, new PromptRules(1000), new SizeRules(List.of(preset(SQUARE), preset(WIDE)), null), List.of(new VideoOutputOption("test-1", new DiscreteIntDomain("discrete_int_domain", List.of(5, 10))), new VideoOutputOption("test-2", new DiscreteIntDomain("discrete_int_domain", List.of(5)))), noReferences(), List.of(VideoOutputFormat.MP4), List.of(false, true), 100L, new VideoDefaults(SQUARE, VideoOutputFormat.MP4, 5, true), evidence()); }
    private static SizePreset preset(RatioSize size) { return new SizePreset("test-" + size.aspectRatio().widthUnits(), size.aspectRatio(), size.resolutionId(), new PixelDimensions(64 * size.aspectRatio().widthUnits(), 64)); }
    private static ReferenceRules noReferences() { return new ReferenceRules(0, 0, List.of(), null, null, null); }
    private static List<CapabilityEvidence> evidence() { return List.of(new CapabilityEvidence(CapabilityEvidenceSourceKind.CONTRACT_VERIFICATION, "synthetic-unregistered-fixture", NOW, "No production provider support")); }
    private static ModelChoice choice(String model) { return new ModelChoice(model, "test-profile", 1L); }
    private static ImageRequirements requirements(String prompt, DraftOutputSize size, Integer count, List<ReferenceAsset> refs, ImageOutputFormat format) { return new ImageRequirements("image_requirements", prompt, size, count, refs, format, null); }
    private static ImageParameters parameters(String prompt, DraftOutputSize size, List<ReferenceAsset> refs, ImageOutputFormat format) { return new ImageParameters("image_parameters", prompt, size, refs, format); }
    private static CreateImagePlanArguments create(ModelChoice choice, ImageRequirements requirements, ImageParameters parameters) { return new CreateImagePlanArguments(new ImageTarget("IMAGE", choice), new ImageIntent("image_intent", "description", requirements), parameters, 0L); }
    private static ImagePlanPatch patchRequirements(ImageOutputFormatChange format) { return new ImagePlanPatch("image_plan_patch", KEEP, new ImageRequirementsPatch(KEEP, KEEP, KEEP, KEEP, format, KEEP), new ImageParametersPatch(KEEP, KEEP, KEEP, KEEP)); }
    private static GenerationPlan plan(PlanConfiguration config) { return new GenerationPlan("generation_plan", 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, PlanState.DRAFT, config, null, NOW, NOW, null); }
}
