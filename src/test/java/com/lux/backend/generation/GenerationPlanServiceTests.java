package com.lux.backend.generation;

import com.lux.backend.asset.AssetRepository;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.common.ApiException;
import com.lux.backend.common.HttpIdempotency;
import com.lux.backend.task.TaskService;
import com.lux.backend.user.UserRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.lux.backend.generation.GenerationPlanContract.*;

class GenerationPlanServiceTests {
    private final String owner=UUID.randomUUID().toString();
    private final String session=UUID.randomUUID().toString();
    private final UUID planId=UUID.randomUUID();
    private final GenerationPlanRepository plans=mock(GenerationPlanRepository.class);
    private final ChatRepository chats=mock(ChatRepository.class);
    private final AssetRepository assets=mock(AssetRepository.class);
    private final GenerationMediaInspector inspector=mock(GenerationMediaInspector.class);
    private final TaskService tasks=mock(TaskService.class);
    private final HttpIdempotency idem=mock(HttpIdempotency.class);
    private final UserRepository users=mock(UserRepository.class);
    private final GenerationPlanCodec codec=new GenerationPlanCodec();
    private final ProviderBinding binding=new ProviderBinding("synthetic", "synthetic-model", "synthetic-adapter");
    private final ModelSelection selection=new ModelSelection("synthetic", "profile",1L);
    private final SessionPlanFocus focus=new SessionPlanFocus(UUID.fromString(session),planId,1L);
    private final ModelCatalog catalog=catalog();
    private final GenerationExecutionSupport support=new GenerationExecutionSupport(List.of(new GenerationExecutionSupport.Route(MediaType.IMAGE,selection,binding,1,true,true)));
    private final GenerationPlanService service=new GenerationPlanService(plans,chats,assets,inspector,catalog,support,tasks,idem,users,codec);

    @BeforeEach void setup() {
        when(idem.originalResponse(anyString(),anyString(),anyString())).thenReturn(Optional.empty());
        when(idem.requestHash(anyString(),any())).thenReturn("0".repeat(64));
        when(idem.executeDecoded(anyString(),anyString(),anyString(),any(),any(),any())).thenAnswer(call -> call.<Supplier<?>>getArgument(5).get());
        when(chats.planFocus(owner,session)).thenReturn(focus);
        when(chats.focusPlan(owner,session,planId)).thenReturn(focus);
        GenerationPlan plan=draft();
        when(plans.get(owner,session,planId)).thenReturn(plan);
        when(plans.lock(owner,session,planId)).thenReturn(plan);
    }
    @Test void originalObservationReplaysBeforeReadingCurrentStateOrInspectingAssets() {
        JavaPlanToolInvocation invocation=new GetModelCatalogInvocation("generation_plan_tool_invocation",UUID.randomUUID(),UUID.fromString(session),"get_model_catalog",new GetModelCatalogArguments(null));
        JavaPlanToolOperationReceipt historical=new JavaPlanToolOperationReceipt("generation_plan_tool_operation_receipt",1,invocation.operationId(),invocation.sessionId(),GenerationPlanToolName.get_model_catalog,"0".repeat(64),new ModelCatalog().snapshot());
        when(idem.originalResponse(owner,invocation.operationId().toString(),"0".repeat(64))).thenReturn(Optional.of(codec.write(historical)));
        assertEquals(historical,service.invoke(owner,session,invocation.toolName(),invocation));
        verifyNoInteractions(plans,chats,assets,inspector,tasks);
    }
    @Test void replayIdentityConflictDoesNotInspectOrMutate() {
        JavaPlanToolInvocation invocation=submit(1L,1L);
        JavaPlanToolOperationReceipt historical=new JavaPlanToolOperationReceipt("generation_plan_tool_operation_receipt",1,invocation.operationId(),invocation.sessionId(),GenerationPlanToolName.submit_generation_plan,"1".repeat(64),catalog.snapshot());
        when(idem.originalResponse(owner,invocation.operationId().toString(),"0".repeat(64))).thenReturn(Optional.of(codec.write(historical)));
        assertEquals(409,assertThrows(ApiException.class,()->service.invoke(owner,session,invocation.toolName(),invocation)).status());
        verifyNoInteractions(plans,chats,assets,inspector,tasks);
    }
    @Test void firstSubmitFreezesOnceAndAssignsDistinctChildIdentities() {
        PlanTaskSubmissionReceipt result=(PlanTaskSubmissionReceipt)service.invoke(owner,session,"submit_generation_plan",submit(1L,1L)).result();
        assertEquals(2L,result.frozenPlanRevision()); assertEquals(3,result.tasks().size());
        assertEquals(3,result.tasks().stream().map(SubmittedTask::taskId).distinct().count());
        assertEquals(3,result.tasks().stream().map(SubmittedTask::taskOperationId).distinct().count());
        verify(plans).save(argThat(plan -> plan.state()==PlanState.FROZEN && plan.revision()==2L && plan.frozenRequest()!=null));
        verify(tasks,times(3)).createPlanTask(eq(owner),eq(session),eq(planId),any(),any(),any());
        verify(chats,never()).focusPlan(anyString(),anyString(),any());
    }
    @Test void newOperationOnFrozenPlanReusesItsRequestAndRevision() {
        GenerationPlan draft=draft();
        NormalizedGenerationRequest request=new GenerationPlanDomain(catalog).freezeRequest(draft,new GenerationPlanDomain.AdmissionFacts(Map.of(),Set.of(),Set.of(binding),Set.of(binding)));
        GenerationPlan frozen=new GenerationPlan(draft.type(),1,planId,draft.userId(),draft.sessionId(),2L,PlanState.FROZEN,draft.configuration(),request,draft.createdAt(),draft.updatedAt(),draft.updatedAt());
        when(plans.get(owner,session,planId)).thenReturn(frozen); when(plans.lock(owner,session,planId)).thenReturn(frozen);
        PlanTaskSubmissionReceipt result=(PlanTaskSubmissionReceipt)service.invoke(owner,session,"submit_generation_plan",submit(2L,1L)).result();
        assertEquals(2L,result.frozenPlanRevision()); verify(plans,never()).save(any());
        verify(tasks,times(3)).createPlanTask(eq(owner),eq(session),eq(planId),any(),any(),same(request));
    }
    @Test void staleFocusProducesPersistableNotPerformedReceiptBeforeWrites() {
        PlanToolRejection result=(PlanToolRejection)service.invoke(owner,session,"submit_generation_plan",submit(1L,0L)).result();
        assertEquals(PlanToolErrorCode.FOCUS_REVISION_CONFLICT,result.code()); assertEquals("NOT_PERFORMED",result.businessExecution());
        verify(plans,never()).save(any()); verifyNoInteractions(tasks);
    }
    @Test void technicalChildFailurePropagatesEvenIfItLooksLikeMissingResource() {
        when(tasks.createPlanTask(anyString(),anyString(),any(),any(),any(),any())).thenThrow(ApiException.notFound());
        assertThrows(ApiException.class,()->service.invoke(owner,session,"submit_generation_plan",submit(1L,1L)));
    }
    @Test void noOpUpdateDoesNotAdvancePlanOrFocus() {
        KeepChange keep=new KeepChange("KEEP");
        ImagePlanPatch patch=new ImagePlanPatch("image_plan_patch",keep,new ImageRequirementsPatch(keep,keep,keep,keep,keep,keep),new ImageParametersPatch(keep,keep,keep,keep));
        UpdateGenerationPlanInvocation invocation=new UpdateGenerationPlanInvocation("generation_plan_tool_invocation",UUID.randomUUID(),UUID.fromString(session),"update_generation_plan",new UpdateGenerationPlanArguments(planId,1L,1L,patch));
        PlanMutationReceipt result=(PlanMutationReceipt)service.invoke(owner,session,invocation.toolName(),invocation).result();
        assertEquals(1L,result.snapshot().plan().revision()); assertEquals(focus,result.focus());
        verify(plans,never()).save(any()); verify(chats,never()).event(anyString(),anyString(),anyString(),any());
    }
    @Test void missingPlanReturnsScopedBusinessRejection() {
        when(plans.lock(owner,session,planId)).thenThrow(ApiException.notFound());
        PlanToolRejection rejection=(PlanToolRejection)service.invoke(owner,session,"submit_generation_plan",submit(1L,1L)).result();
        assertEquals(PlanToolErrorCode.RESOURCE_NOT_FOUND,rejection.code()); verifyNoInteractions(tasks);
    }
    @Test void paginationUsesImmutableLastReturnedCreationKeyAndBoundedLimit() {
        GenerationPlan first=draft(); GenerationPlan second=draft();
        when(plans.page(owner,session,null,2)).thenReturn(List.of(first,second));
        GenerationPlanReadResult page=service.read(owner,session,new SessionPlansQuery("list",null,1));
        assertEquals(1,page.plans().size()); assertEquals(new PlansCursor(first.createdAt(),first.id()),page.nextCursor());
        assertThrows(ApiException.class,()->service.read(owner,session,new SessionPlansQuery("list",null,51)));
    }
    @Test void frozenCrossMediaCreatesNewDraftAndLeavesSourceUntouched() {
        GenerationPlan frozen=frozen(); when(plans.get(owner,session,planId)).thenReturn(frozen); when(plans.lock(owner,session,planId)).thenReturn(frozen);
        when(chats.focusPlan(eq(owner),eq(session),any())).thenAnswer(call -> new SessionPlanFocus(UUID.fromString(session),call.getArgument(2),2L));
        ChangeGenerationTargetInvocation invocation=new ChangeGenerationTargetInvocation("generation_plan_tool_invocation",UUID.randomUUID(),UUID.fromString(session),"change_generation_target",new ChangeGenerationTargetArguments(planId,2L,1L,new VideoTarget("VIDEO",null)));
        PlanMutationReceipt result=(PlanMutationReceipt)service.invoke(owner,session,invocation.toolName(),invocation).result();
        assertEquals(PlanMutationReceiptAction.MEDIA_PLAN_CREATED,result.action()); assertEquals(planId,result.sourcePlanId());
        assertEquals(PlanState.DRAFT,result.snapshot().plan().state()); assertNull(result.snapshot().plan().frozenRequest());
        assertNotEquals(planId,result.snapshot().plan().id()); assertEquals(3,((VideoPlanConfiguration)result.snapshot().plan().configuration()).intent().requirements().requestedFileCount());
        verify(plans,never()).save(any()); verify(plans).insert(result.snapshot().plan());
    }
    @Test void cloneCopiesFrozenConfigurationWithoutReapplyingDefaultsOrChangingSource() {
        GenerationPlan frozen=frozen(); when(plans.get(owner,session,planId)).thenReturn(frozen); when(plans.lock(owner,session,planId)).thenReturn(frozen);
        when(chats.focusPlan(eq(owner),eq(session),any())).thenAnswer(call -> new SessionPlanFocus(UUID.fromString(session),call.getArgument(2),2L));
        CloneGenerationPlanInvocation invocation=new CloneGenerationPlanInvocation("generation_plan_tool_invocation",UUID.randomUUID(),UUID.fromString(session),"clone_generation_plan",new CloneGenerationPlanArguments(planId,2L,1L));
        PlanMutationReceipt result=(PlanMutationReceipt)service.invoke(owner,session,invocation.toolName(),invocation).result();
        assertEquals(frozen.configuration(),result.snapshot().plan().configuration()); assertEquals(PlanState.DRAFT,result.snapshot().plan().state());
        assertEquals(1L,result.snapshot().plan().revision()); assertNull(result.snapshot().plan().frozenAt()); assertTrue(result.appliedDefaults().isEmpty());
        verify(plans,never()).save(any());
    }
    @Test void focusingAlreadyFocusedPlanDoesNotPublishSpuriousChange() {
        FocusGenerationPlanInvocation invocation=new FocusGenerationPlanInvocation("generation_plan_tool_invocation",UUID.randomUUID(),UUID.fromString(session),"focus_generation_plan",new FocusGenerationPlanArguments(planId,1L));
        PlanFocusReceipt result=(PlanFocusReceipt)service.invoke(owner,session,invocation.toolName(),invocation).result();
        assertEquals(focus,result.focus()); verify(chats,never()).event(anyString(),anyString(),anyString(),any());
    }
    @Test void nonFocusedCloneWithCurrentRevisionsRejectsBeforeAnyBusinessMutation() {
        SessionPlanFocus anotherFocus=new SessionPlanFocus(UUID.fromString(session),UUID.randomUUID(),1L);
        when(chats.planFocus(owner,session)).thenReturn(anotherFocus);
        CloneGenerationPlanInvocation invocation=new CloneGenerationPlanInvocation("generation_plan_tool_invocation",UUID.randomUUID(),UUID.fromString(session),"clone_generation_plan",new CloneGenerationPlanArguments(planId,1L,1L));
        PlanToolRejection rejection=(PlanToolRejection)service.invoke(owner,session,invocation.toolName(),invocation).result();
        assertEquals(PlanToolErrorCode.PLAN_NOT_FOCUSED,rejection.code()); assertEquals("NOT_PERFORMED",rejection.businessExecution());
        verify(plans,never()).insert(any()); verify(plans,never()).save(any());
        verify(chats,never()).focusPlan(anyString(),anyString(),any()); verify(chats,never()).event(anyString(),anyString(),anyString(),any());
        verifyNoInteractions(tasks);
    }
    @Test void authoritativeReadMethodsDeclareOneRepeatableReadSnapshot() throws Exception {
        org.springframework.transaction.annotation.AnnotationTransactionAttributeSource source=new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        for(java.lang.reflect.Method method:List.of(GenerationPlanService.class.getMethod("context",String.class,String.class),GenerationPlanService.class.getMethod("read",String.class,String.class,PlanQuery.class))) {
            org.springframework.transaction.interceptor.TransactionAttribute attribute=source.getTransactionAttribute(method,GenerationPlanService.class);
            assertNotNull(attribute); assertTrue(attribute.isReadOnly());
            assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ,attribute.getIsolationLevel());
        }
    }
    @Test void cachedAuthoritativeReadsNeverStartMediaProbeOrByteInspection() {
        GenerationExecutionSupport videoSupport=new GenerationExecutionSupport(List.of(new GenerationExecutionSupport.Route(MediaType.VIDEO,selection,binding,1,true,true)));
        GenerationPlanService reads=new GenerationPlanService(plans,chats,assets,inspector,catalog,videoSupport,tasks,idem,users,codec);
        assertEquals(focus,reads.context(owner,session).focus());
        assertEquals(draft(),reads.read(owner,session,new PlanByIdQuery("by_id",planId)).plans().get(0).plan());
        verifyNoInteractions(inspector);
    }
    private GenerationPlan frozen() {
        GenerationPlan draft=draft(); NormalizedGenerationRequest request=new GenerationPlanDomain(catalog).freezeRequest(draft,new GenerationPlanDomain.AdmissionFacts(Map.of(),Set.of(),Set.of(binding),Set.of(binding)));
        return new GenerationPlan(draft.type(),1,planId,draft.userId(),draft.sessionId(),2L,PlanState.FROZEN,draft.configuration(),request,draft.createdAt(),draft.updatedAt(),draft.updatedAt());
    }
    private SubmitGenerationPlanInvocation submit(Long revision,Long focusRevision) { return new SubmitGenerationPlanInvocation("generation_plan_tool_invocation",UUID.randomUUID(),UUID.fromString(session),"submit_generation_plan",new SubmitGenerationPlanArguments(planId,revision,focusRevision)); }
    private GenerationPlan draft() {
        CreateImagePlanArguments arguments=new CreateImagePlanArguments(new ImageTarget("IMAGE",new ModelChoice("synthetic","profile",1L)),new ImageIntent("image_intent","test",new ImageRequirements("image_requirements",null,null,3,null,null,null)),new ImageParameters("image_parameters","prompt",null,List.of(),null),0L);
        PlanConfiguration configuration=new GenerationPlanDomain(catalog).create(arguments).configuration(); Instant now=Instant.parse("2026-10-13T00:00:00Z");
        return new GenerationPlan("generation_plan",1,planId,UUID.fromString(owner),UUID.fromString(session),1L,PlanState.DRAFT,configuration,null,now,now,null);
    }
    private ModelCatalog catalog() {
        SizePreset preset=new SizePreset("square",new AspectRatio(1,1),"small",new PixelDimensions(64,64));
        VerifiedImageProfile profile=new VerifiedImageProfile("verified_image_profile","profile",ImageMode.IMAGE,new PromptRules(1000),new SizeRules(List.of(preset),null),new ReferenceRules(0,0,List.of(),null,null,null),List.of(ImageOutputFormat.PNG),new ImageDefaults(new RatioSize("ratio_size",preset.aspectRatio(),preset.resolutionId()),ImageOutputFormat.PNG),List.of(new CapabilityEvidence(CapabilityEvidenceSourceKind.CONTRACT_VERIFICATION,"synthetic-fixture",Instant.parse("2026-10-13T00:00:00Z"),"Synthetic only")));
        return new ModelCatalog(new ModelCatalogSnapshot("model_catalog_snapshot",1L,new GenerationSubmissionPolicy(32),List.of(new ImageModelDefinition("image_model_definition","synthetic","Synthetic",1L,true,binding,List.of(profile)))));
    }
}
