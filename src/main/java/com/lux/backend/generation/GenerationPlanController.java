package com.lux.backend.generation;

import com.lux.backend.common.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static com.lux.backend.generation.GenerationPlanContract.*;

/** 严格协议入口；原始 JSON 仅在此立即解码成封闭的具名调用。 */
@RestController
public class GenerationPlanController {
    private final GenerationPlanService service;
    private final GenerationPlanCodec codec;
    public GenerationPlanController(GenerationPlanService service, GenerationPlanCodec codec) {
        this.service=service; this.codec=codec;
    }
    @PostMapping("/internal/v2/users/{user}/sessions/{session}/generation-plan-tools/{tool}")
    public JavaPlanToolOperationReceipt invoke(@PathVariable String user,@PathVariable String session,
        @PathVariable String tool,@RequestBody String body) {
        JavaPlanToolInvocation invocation;
        try { invocation=codec.decodeInvocation(body); }
        catch(RuntimeException malformed) { throw ApiException.badRequest("Malformed generation plan invocation"); }
        return service.invoke(user,session,tool,invocation);
    }
    @GetMapping("/internal/v2/users/{user}/sessions/{session}/generation-plan-tool-operations/{operation}")
    public JavaPlanToolOperationReceipt receipt(@PathVariable String user,@PathVariable String session,@PathVariable String operation) {
        return service.receipt(user,session,operation);
    }
    @GetMapping("/internal/v2/users/{user}/sessions/{session}/generation-plan-context")
    public GenerationPlanContext context(@PathVariable String user,@PathVariable String session) { return service.context(user,session); }
    @GetMapping("/api/v1/users/{user}/sessions/{session}/plans/{id}")
    public GenerationPlanSnapshot plan(@PathVariable String user,@PathVariable String session,@PathVariable UUID id) {
        return service.read(user,session,new PlanByIdQuery("by_id",id)).plans().get(0);
    }
    @GetMapping("/api/v1/users/{user}/sessions/{session}/plans")
    public GenerationPlanReadResult plans(@PathVariable String user,@PathVariable String session,
        @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) Instant beforeCreatedAt,
        @RequestParam(required=false) UUID beforeId) {
        if((beforeCreatedAt==null)!=(beforeId==null)) throw ApiException.badRequest("Both cursor fields are required");
        PlansCursor cursor=beforeId==null?null:new PlansCursor(beforeCreatedAt,beforeId);
        return service.read(user,session,new SessionPlansQuery("list",cursor,limit));
    }
    @GetMapping("/api/v1/users/{user}/sessions/{session}/plan-focus")
    public SessionPlanFocus focus(@PathVariable String user,@PathVariable String session) { return service.context(user,session).focus(); }
}
