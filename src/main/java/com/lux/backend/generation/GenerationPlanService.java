package com.lux.backend.generation;

import com.lux.backend.asset.AssetRecord;
import com.lux.backend.asset.AssetRepository;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.chat.SessionChange;
import com.lux.backend.common.ApiException;
import com.lux.backend.common.HttpIdempotency;
import com.lux.backend.common.Ids;
import com.lux.backend.common.Timestamps;
import com.lux.backend.task.TaskService;
import com.lux.backend.user.UserRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.lux.backend.generation.GenerationPlanContract.*;

/** 计划操作的数据库边界：父回执、焦点、冻结与子任务在同一事务提交。 */
@Service
public class GenerationPlanService {
    private static final String NAMESPACE = "generation-plan-tools-v1";
    private final GenerationPlanRepository plans;
    private final ChatRepository chats;
    private final AssetRepository assets;
    private final GenerationMediaInspector inspector;
    private final ModelCatalog catalog;
    private final GenerationExecutionSupport support;
    private final TaskService tasks;
    private final HttpIdempotency idem;
    private final UserRepository users;
    private final GenerationPlanCodec codec;
    private final GenerationPlanDomain domain;
    private volatile boolean mediaProbeAvailable;
    public GenerationPlanService(GenerationPlanRepository plans, ChatRepository chats, AssetRepository assets,
        GenerationMediaInspector inspector, ModelCatalog catalog, GenerationExecutionSupport support,
        TaskService tasks, HttpIdempotency idem, UserRepository users, GenerationPlanCodec codec) {
        this.plans=plans; this.chats=chats; this.assets=assets; this.inspector=inspector; this.catalog=catalog;
        this.support=support; this.tasks=tasks; this.idem=idem; this.users=users; this.codec=codec; this.domain=new GenerationPlanDomain(catalog);
    }
    /** 重放在最新状态和外部检查之前；不同完整请求身份仍由幂等存储拒绝。 */
    public JavaPlanToolOperationReceipt invoke(String owner, String session, String tool, JavaPlanToolInvocation invocation) {
        owner=Ids.require(owner); session=Ids.require(session); users.get(owner);
        if (!invocation.sessionId().toString().equals(session) || !invocation.toolName().equals(tool)) throw ApiException.badRequest("Invocation does not match route");
        GenerationPlanOperationIdentity identity=new GenerationPlanOperationIdentity(UUID.fromString(owner), invocation);
        String hash=idem.requestHash(NAMESPACE, identity);
        Optional<String> original=idem.originalResponse(owner, invocation.operationId().toString(), hash);
        if (original.isPresent()) {
            JavaPlanToolOperationReceipt receipt=decodeReceipt(original.get());
            if (!hash.equals(receipt.requestHash())) throw ApiException.conflict("Operation identity has different input");
            return receipt;
        }
        refreshProbeAvailability();
        PreparedFacts prepared=prepare(owner, session, invocation);
        String trustedOwner=owner; String trustedSession=session;
        return idem.executeDecoded(owner, invocation.operationId().toString(), NAMESPACE, identity, this::decodeReceipt,
            () -> execute(trustedOwner, trustedSession, invocation, hash, prepared));
    }
    private JavaPlanToolOperationReceipt decodeReceipt(String value) { return codec.read(value, JavaPlanToolOperationReceipt.class); }
    public JavaPlanToolOperationReceipt receipt(String owner, String session, String operation) {
        users.get(Ids.require(owner)); chats.planFocus(owner, Ids.require(session));
        JavaPlanToolOperationReceipt receipt=decodeReceipt(idem.originalResponse(owner, Ids.require(operation)).orElseThrow(ApiException::notFound));
        if (!receipt.sessionId().toString().equals(session)) throw ApiException.notFound();
        return receipt;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GenerationPlanContext context(String owner, String session) {
        users.get(Ids.require(owner)); SessionPlanFocus focus=chats.planFocus(owner, Ids.require(session));
        return new GenerationPlanContext(focus, focus.activePlanId()==null ? null : cachedSnapshot(owner,plans.get(owner,session,focus.activePlanId())), catalog.snapshot());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GenerationPlanReadResult read(String owner,String session,PlanQuery query) {
        users.get(Ids.require(owner)); return readResult(owner,Ids.require(session),query,GenerationPlanDomain.AdmissionFacts.unavailable());
    }
    private record PreparedFacts(Map<UUID,VerifiedMediaMetadata> metadata, Map<UUID,String> checksums, Set<UUID> unavailable) { }
    /** 字节检查在重试事务外执行；事务内重新核验归属与内容摘要。 */
    private PreparedFacts prepare(String owner,String session,JavaPlanToolInvocation invocation) {
        Set<UUID> references=new HashSet<>();
        UUID id=planId(invocation);
        if(id!=null) { try { collect(plans.get(owner,session,id).configuration(),references); } catch(ApiException missing) { if(missing.status()!=404) throw missing; } }
        if(invocation instanceof ReadGenerationPlanInvocation read) {
            PlanQuery query=read.arguments().query();
            if(query instanceof SessionPlansQuery list) {
                for(GenerationPlan candidate:plans.page(owner,session,list.cursor(),list.limit())) collect(candidate.configuration(),references);
            } else {
                UUID readId=query instanceof PlanByIdQuery byId ? byId.planId() : chats.planFocus(owner,session).activePlanId();
                if(readId!=null) { try { collect(plans.get(owner,session,readId).configuration(),references); }
                    catch(ApiException missing) { if(missing.status()!=404) throw missing; } }
            }
        }
        if(invocation instanceof CreateGenerationPlanInvocation create) {
            if(create.arguments() instanceof CreateImagePlanArguments image) { collect(image.parameters().references(),references); collect(image.intent().requirements().references(),references); }
            else { CreateVideoPlanArguments video=(CreateVideoPlanArguments)create.arguments(); collect(video.parameters().references(),references); collect(video.intent().requirements().references(),references); }
        }
        if(invocation instanceof UpdateGenerationPlanInvocation update) {
            PlanPatch patch=update.arguments().patch();
            if(patch instanceof ImagePlanPatch image) { collect(image.parameters().references(),references); collect(image.requirements().references(),references); }
            else { VideoPlanPatch video=(VideoPlanPatch)patch; collect(video.parameters().references(),references); collect(video.requirements().references(),references); }
        }
        Map<UUID,VerifiedMediaMetadata> metadata=new HashMap<>(); Map<UUID,String> checksums=new HashMap<>(); Set<UUID> unavailable=new HashSet<>();
        for(UUID assetId:references) {
            Optional<AssetRecord> asset=assets.find(owner,assetId.toString());
            if(asset.isEmpty()) { unavailable.add(assetId); continue; }
            checksums.put(assetId,asset.get().sha256());
            Optional<VerifiedMediaMetadata> cached=assets.inspected(owner,assetId.toString());
            if(cached.isPresent()) metadata.put(assetId,cached.get());
            else { try { metadata.put(assetId,inspector.inspect(asset.get())); } catch(GenerationMediaInspector.MediaInspectionException failure) { unavailable.add(assetId); } catch(java.io.IOException technical) { throw new java.io.UncheckedIOException(technical); } }
        }
        return new PreparedFacts(Map.copyOf(metadata),Map.copyOf(checksums),Set.copyOf(unavailable));
    }
    private void collect(List<ReferenceAsset> references,Set<UUID> ids) { if(references!=null) references.forEach(reference -> ids.add(reference.assetId())); }
    private void collect(ReferencesChange change,Set<UUID> ids) { if(change instanceof SetReferences set) collect(set.value(),ids); }
    private void collect(PlanConfiguration configuration,Set<UUID> ids) {
        if(configuration instanceof ImagePlanConfiguration image) { collect(image.parameters().references(),ids); collect(image.intent().requirements().references(),ids); }
        else { VideoPlanConfiguration video=(VideoPlanConfiguration)configuration; collect(video.parameters().references(),ids); collect(video.intent().requirements().references(),ids); }
    }
    private GenerationPlanDomain.AdmissionFacts facts(PlanConfiguration configuration,PreparedFacts prepared) {
        return new GenerationPlanDomain.AdmissionFacts(prepared.metadata(),prepared.unavailable(),support.execution(configuration),verifiers(configuration));
    }
    private JavaPlanToolOperationReceipt execute(String owner,String session,JavaPlanToolInvocation invocation,String hash,PreparedFacts prepared) {
        assets.coordinateOwner(owner); chats.sessionForMutation(owner,session);
        SessionPlanFocus focus=chats.planFocus(owner,session);
        GenerationPlan plan=null; GenerationPlanToolResult result;
        try {
            UUID id=planId(invocation);
            if(id!=null) {
                try { plan=plans.lock(owner,session,id); }
                catch(ApiException missing) { if(missing.status()!=404) throw missing; throw new GenerationPlanDomain.DomainException(PlanToolErrorCode.RESOURCE_NOT_FOUND,"Resource not found",List.of()); }
            }
            for(Map.Entry<UUID,String> entry:prepared.checksums().entrySet()) {
                AssetRecord current;
                try { current=assets.get(owner,entry.getKey().toString()); }
                catch(ApiException missing) { if(missing.status()!=404) throw missing; throw new GenerationPlanDomain.DomainException(PlanToolErrorCode.RESOURCE_NOT_FOUND,"Reference asset is unavailable",List.of()); }
                if(!current.sha256().equals(entry.getValue())) throw ApiException.conflict("Asset changed during inspection");
            }
            result=perform(owner,session,invocation,focus,plan,prepared);
        } catch(GenerationPlanDomain.DomainException rejection) {
            result=new PlanToolRejection("generation_plan_tool_rejection",rejection.code(),rejection.getMessage(),plan==null?null:plan.revision(),focus.revision(),rejection.issues(),"NOT_PERFORMED");
        }
        return new JavaPlanToolOperationReceipt("generation_plan_tool_operation_receipt",1,invocation.operationId(),invocation.sessionId(),GenerationPlanToolName.valueOf(invocation.toolName()),hash,result);
    }
    private GenerationPlanToolResult perform(String owner,String session,JavaPlanToolInvocation invocation,SessionPlanFocus focus,GenerationPlan plan,PreparedFacts prepared) {
        if(invocation instanceof GetModelCatalogInvocation get) return catalog.snapshot(get.arguments().mediaType());
        if(invocation instanceof ReadGenerationPlanInvocation read) {
            GenerationPlanReadResult result;
            try { result=readResult(owner,session,read.arguments().query(),new GenerationPlanDomain.AdmissionFacts(prepared.metadata(),prepared.unavailable(),Set.of(),Set.of())); }
            catch(ApiException missing) { if(missing.status()!=404) throw missing; throw new GenerationPlanDomain.DomainException(PlanToolErrorCode.RESOURCE_NOT_FOUND,"Resource not found",List.of()); }
            saveFacts(owner,prepared); return result;
        }
        long expectedFocus=expectedFocus(invocation);
        require(expectedFocus==focus.revision(),PlanToolErrorCode.FOCUS_REVISION_CONFLICT,"Focus revision changed");
        Long revision=expectedPlan(invocation);
        if(revision!=null) require(revision.equals(plan.revision()),PlanToolErrorCode.PLAN_REVISION_CONFLICT,"Plan revision changed");
        if(invocation instanceof FocusGenerationPlanInvocation) return new PlanFocusReceipt("generation_plan_focus_receipt",focus(owner,session,focus,plan.id()));
        if(invocation instanceof SubmitGenerationPlanInvocation) {
            require(plan.id().equals(focus.activePlanId()),PlanToolErrorCode.PLAN_NOT_FOCUSED,"Plan is not focused");
            GenerationPlanDomain.AdmissionFacts facts=facts(plan.configuration(),prepared);
            NormalizedGenerationRequest request=domain.freezeRequest(plan,facts);
            int count=domain.requestedFileCount(plan.configuration());
            List<SubmittedTask> submitted=new ArrayList<>();
            for(int i=0;i<count;i++) submitted.add(new SubmittedTask(i,UUID.randomUUID(),UUID.randomUUID()));
            GenerationPlan frozen=plan.state()==PlanState.FROZEN?plan:changed(plan,plan.configuration(),PlanState.FROZEN,request,Timestamps.now());
            // 所有可证明的业务拒绝已结束；后续写入失败必须回滚并保持 UNKNOWN。
            saveFacts(owner,prepared);
            if(frozen!=plan) { plans.save(frozen); notifyPlan(owner,session,frozen); }
            for(SubmittedTask task:submitted) tasks.createPlanTask(owner,session,frozen.id(),task.taskId(),task.taskOperationId(),request);
            return new PlanTaskSubmissionReceipt("plan_task_submission_receipt",frozen.id(),frozen.revision(),true,count,submitted,focus);
        }
        ConfigurationChange change; PlanMutationReceiptAction action; UUID source=null; boolean create;
        if(invocation instanceof CreateGenerationPlanInvocation creation) {
            change=configurationChange(() -> domain.create(creation.arguments(),new GenerationPlanDomain.AdmissionFacts(prepared.metadata(),prepared.unavailable(),Set.of(),Set.of()))); action=PlanMutationReceiptAction.CREATED; create=true;
        } else if(invocation instanceof CloneGenerationPlanInvocation) {
            require(plan.id().equals(focus.activePlanId()),PlanToolErrorCode.PLAN_NOT_FOCUSED,"Plan is not focused");
            source=plan.id(); change=domain.cloneConfiguration(plan.configuration()); action=PlanMutationReceiptAction.CLONED; create=true;
        } else {
            boolean crossMedia=invocation instanceof ChangeGenerationTargetInvocation target &&
                ((plan.configuration() instanceof ImagePlanConfiguration)!=(target.arguments().target() instanceof ImageTarget));
            require(crossMedia || plan.state()==PlanState.DRAFT,PlanToolErrorCode.PLAN_FROZEN,"Frozen plan is immutable");
            require(plan.id().equals(focus.activePlanId()),PlanToolErrorCode.PLAN_NOT_FOCUSED,"Plan is not focused");
            if(invocation instanceof UpdateGenerationPlanInvocation update) { change=configurationChange(() -> domain.update(plan.configuration(),update.arguments().patch(),facts(plan.configuration(),prepared))); action=PlanMutationReceiptAction.UPDATED; create=false; }
            else { ChangeGenerationTargetInvocation target=(ChangeGenerationTargetInvocation)invocation; change=configurationChange(() -> domain.retarget(plan.configuration(),target.arguments().target(),facts(plan.configuration(),prepared))); create=(plan.configuration() instanceof ImagePlanConfiguration)!=(change.configuration() instanceof ImagePlanConfiguration); action=create?PlanMutationReceiptAction.MEDIA_PLAN_CREATED:PlanMutationReceiptAction.MODEL_CHANGED; source=create?plan.id():null; }
        }
        GenerationPlan next=create?draft(owner,session,change.configuration()):change.configuration().equals(plan.configuration())?plan:changed(plan,change.configuration(),PlanState.DRAFT,null,null);
        GenerationPlanSnapshot snapshot=snapshot(next,facts(next.configuration(),prepared));
        saveFacts(owner,prepared);
        if(create) { plans.insert(next); notifyPlan(owner,session,next); } else if(next!=plan) { plans.save(next); notifyPlan(owner,session,next); }
        SessionPlanFocus nextFocus=focus(owner,session,focus,next.id());
        return new PlanMutationReceipt("generation_plan_mutation_receipt",action,source,snapshot,nextFocus,change.clearedFields(),change.nonMigratedRequirementFields(),change.appliedDefaults());
    }
    /** 只有纯配置规则能够把可证明的输入错误转换为业务拒绝，数据库写入不经过此捕获。 */
    private ConfigurationChange configurationChange(java.util.function.Supplier<ConfigurationChange> change) {
        try { return change.get(); }
        catch(GenerationPlanDomain.DomainException rejection) { throw rejection; }
        catch(IllegalArgumentException invalid) { throw new GenerationPlanDomain.DomainException(PlanToolErrorCode.INVALID_ARGUMENT,invalid.getMessage(),List.of()); }
    }
    private void saveFacts(String owner,PreparedFacts prepared) { prepared.metadata().forEach((id,fact) -> assets.saveInspection(owner,id.toString(),prepared.checksums().get(id),fact)); }
    private SessionPlanFocus focus(String owner,String session,SessionPlanFocus previous,UUID id) {
        SessionPlanFocus result=chats.focusPlan(owner,session,id);
        if(!result.equals(previous)) chats.event(owner,session,"PLAN_FOCUS_CHANGED",new SessionChange.PlanFocusChanged(result)); return result;
    }
    private void notifyPlan(String owner,String session,GenerationPlan plan) { chats.event(owner,session,"PLAN_UPDATED",new SessionChange.PlanChanged(plan.id().toString(),plan.revision())); }
    private GenerationPlan draft(String owner,String session,PlanConfiguration configuration) { Instant now=Timestamps.now(); return new GenerationPlan("generation_plan",1,UUID.randomUUID(),UUID.fromString(owner),UUID.fromString(session),1L,PlanState.DRAFT,configuration,null,now,now,null); }
    private GenerationPlan changed(GenerationPlan plan,PlanConfiguration configuration,PlanState state,NormalizedGenerationRequest request,Instant frozenAt) { return new GenerationPlan("generation_plan",1,plan.id(),plan.userId(),plan.sessionId(),Math.addExact(plan.revision(),1),state,configuration,request,plan.createdAt(),Timestamps.now(),frozenAt); }
    /** 可用性探测发生在事务外，事务内仅消费已捕获的可用性事实。 */
    @jakarta.annotation.PostConstruct
    private void refreshProbeAvailability() { if(support.requiresMediaProbe()) mediaProbeAvailable=inspector.probeAvailable(); }
    private Set<ProviderBinding> verifiers(PlanConfiguration configuration) {
        return configuration instanceof VideoPlanConfiguration && !mediaProbeAvailable ? Set.of() : support.verifiers(configuration);
    }
    private GenerationPlanSnapshot snapshotWithFacts(String owner,GenerationPlan plan,GenerationPlanDomain.AdmissionFacts prepared) {
        if(prepared.metadata().isEmpty() && prepared.unavailableAssets().isEmpty()) return cachedSnapshot(owner,plan);
        return snapshot(plan,new GenerationPlanDomain.AdmissionFacts(prepared.metadata(),prepared.unavailableAssets(),support.execution(plan.configuration()),verifiers(plan.configuration())));
    }
    /** 只读取已验证缓存事实；读取接口不会在数据库事务中启动存储或进程 IO。 */
    private GenerationPlanSnapshot cachedSnapshot(String owner,GenerationPlan plan) {
        Set<UUID> ids=new HashSet<>(); collect(plan.configuration(),ids);
        Map<UUID,VerifiedMediaMetadata> metadata=new HashMap<>(); Set<UUID> unavailable=new HashSet<>();
        for(UUID id:ids) {
            if(assets.find(owner,id.toString()).isEmpty()) unavailable.add(id);
            else assets.inspected(owner,id.toString()).ifPresent(fact -> metadata.put(id,fact));
        }
        return snapshot(plan,new GenerationPlanDomain.AdmissionFacts(metadata,unavailable,support.execution(plan.configuration()),verifiers(plan.configuration())));
    }
    private GenerationPlanSnapshot snapshot(GenerationPlan plan,GenerationPlanDomain.AdmissionFacts facts) { return new GenerationPlanSnapshot(plan,domain.validation(plan,facts)); }
    private GenerationPlanReadResult readResult(String owner,String session,PlanQuery query,GenerationPlanDomain.AdmissionFacts facts) {
        SessionPlanFocus focus=chats.planFocus(owner,session); List<GenerationPlan> values; PlansCursor cursor=null;
        if(query instanceof SessionPlansQuery list) {
            if(list.limit()<1||list.limit()>50) throw ApiException.badRequest("Limit must be 1 through 50");
            values=plans.page(owner,session,list.cursor(),list.limit()+1);
            if(values.size()>list.limit()) { values=values.subList(0,list.limit()); GenerationPlan last=values.get(values.size()-1); cursor=new PlansCursor(last.createdAt(),last.id()); }
        } else { UUID id=query instanceof PlanByIdQuery byId?byId.planId():focus.activePlanId(); values=id==null?List.of():List.of(plans.get(owner,session,id)); }
        return new GenerationPlanReadResult("generation_plan_read_result",focus,values.stream().map(plan -> snapshotWithFacts(owner,plan,facts)).toList(),cursor);
    }
    private void require(boolean condition,PlanToolErrorCode code,String message) { if(!condition) throw new GenerationPlanDomain.DomainException(code,message,List.of()); }
    private UUID planId(JavaPlanToolInvocation invocation) {
        if(invocation instanceof UpdateGenerationPlanInvocation value) return value.arguments().planId();
        if(invocation instanceof ChangeGenerationTargetInvocation value) return value.arguments().planId();
        if(invocation instanceof CloneGenerationPlanInvocation value) return value.arguments().sourcePlanId();
        if(invocation instanceof FocusGenerationPlanInvocation value) return value.arguments().planId();
        if(invocation instanceof SubmitGenerationPlanInvocation value) return value.arguments().planId(); return null;
    }
    private Long expectedPlan(JavaPlanToolInvocation invocation) {
        if(invocation instanceof UpdateGenerationPlanInvocation value) return value.arguments().expectedPlanRevision();
        if(invocation instanceof ChangeGenerationTargetInvocation value) return value.arguments().expectedPlanRevision();
        if(invocation instanceof CloneGenerationPlanInvocation value) return value.arguments().expectedSourcePlanRevision();
        if(invocation instanceof SubmitGenerationPlanInvocation value) return value.arguments().expectedPlanRevision(); return null;
    }
    private long expectedFocus(JavaPlanToolInvocation invocation) {
        if(invocation instanceof CreateGenerationPlanInvocation value) return value.arguments().expectedFocusRevision();
        if(invocation instanceof UpdateGenerationPlanInvocation value) return value.arguments().expectedFocusRevision();
        if(invocation instanceof ChangeGenerationTargetInvocation value) return value.arguments().expectedFocusRevision();
        if(invocation instanceof CloneGenerationPlanInvocation value) return value.arguments().expectedFocusRevision();
        if(invocation instanceof FocusGenerationPlanInvocation value) return value.arguments().expectedFocusRevision();
        return ((SubmitGenerationPlanInvocation)invocation).arguments().expectedFocusRevision();
    }
}
