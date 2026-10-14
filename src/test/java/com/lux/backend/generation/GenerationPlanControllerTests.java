package com.lux.backend.generation;

import com.lux.backend.common.ApiErrors;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.lux.backend.generation.GenerationPlanContract.*;

class GenerationPlanControllerTests {
    private final GenerationPlanService service=mock(GenerationPlanService.class);
    private final GenerationPlanCodec codec=new GenerationPlanCodec();
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new GenerationPlanController(service,codec)).setControllerAdvice(new ApiErrors()).build();
    private final UUID owner=UUID.randomUUID();
    private final UUID session=UUID.randomUUID();
    private final GetModelCatalogInvocation invocation=new GetModelCatalogInvocation("generation_plan_tool_invocation",UUID.randomUUID(),session,"get_model_catalog",new GetModelCatalogArguments(null));
    private final String route="/internal/v2/users/"+owner+"/sessions/"+session+"/generation-plan-tools/get_model_catalog";

    @Test void validBusinessRejectionIsHttp200TypedReceipt() throws Exception {
        PlanToolRejection rejection=new PlanToolRejection("generation_plan_tool_rejection",PlanToolErrorCode.FOCUS_REVISION_CONFLICT,"Focus changed",null,1L,List.of(),"NOT_PERFORMED");
        JavaPlanToolOperationReceipt receipt=new JavaPlanToolOperationReceipt("generation_plan_tool_operation_receipt",1,invocation.operationId(),session,GenerationPlanToolName.get_model_catalog,"0".repeat(64),rejection);
        when(service.invoke(owner.toString(),session.toString(),"get_model_catalog",invocation)).thenReturn(receipt);
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(codec.write(invocation)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.business_execution").value("NOT_PERFORMED"))
            .andExpect(jsonPath("$.operation_id").value(invocation.operationId().toString()));
    }
    @Test void missingNullableFieldsAndUnknownFieldsRejectBeforeService() throws Exception {
        String valid=codec.write(invocation);
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(valid.replace("\"media_type\":null","\"extra\":null"))).andExpect(status().isBadRequest());
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(valid.replace("\"media_type\":null","\"media_type\":null,\"extra\":true"))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void scopedReceiptLookupPreservesSessionBinding() throws Exception {
        when(service.receipt(owner.toString(),session.toString(),invocation.operationId().toString())).thenThrow(com.lux.backend.common.ApiException.notFound());
        mvc.perform(get("/internal/v2/users/"+owner+"/sessions/"+session+"/generation-plan-tool-operations/"+invocation.operationId())).andExpect(status().isNotFound());
    }
    @Test void partialPaginationCursorRejectsBeforeRead() throws Exception {
        mvc.perform(get("/api/v1/users/"+owner+"/sessions/"+session+"/plans").param("beforeId",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        verify(service,never()).read(anyString(),anyString(),any());
    }
}
