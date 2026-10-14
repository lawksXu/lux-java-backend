package com.lux.backend;

import com.lux.backend.chat.ConversationContent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.lux.backend.user.UserService;
import com.lux.backend.asset.AssetService;
import com.lux.backend.asset.AssetStore;
import com.lux.backend.asset.S3AssetStore;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.chat.SessionService;
import com.lux.backend.chat.SessionEventStream;
import com.lux.backend.common.ApiException;
import com.lux.backend.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"lux.local.bootstrap-user=false","lux.workers.enabled=false",
        "lux.storage.provider=s3", "lux.storage.s3.access-key=wiring-test",
        "lux.storage.s3.secret-key=wiring-test-only",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.flyway.enabled=false","spring.sql.init.mode=never","spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
class ApplicationWiringTests {
    private static final String OWNER="aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OTHER="bbbbbbbb-0000-0000-0000-000000000002";
    private static final String RESOURCE="cccccccc-0000-0000-0000-000000000003";
    @MockitoBean DataSource dataSource;
    @MockitoBean KafkaAdmin kafkaAdmin;
    @MockitoBean UserService users;
    @MockitoBean ChatRepository chats;
    @MockitoBean SessionService sessions;
    @MockitoBean TaskService tasks;
    @MockitoBean AssetService assets;
    @MockitoBean SessionEventStream streams;
    @Autowired MockMvc mvc;
    @Autowired AssetStore assetStore;

    @BeforeEach void knownUsers() {
        when(users.get(OWNER)).thenReturn(new UserService.UserRecord(OWNER,"Owner",Instant.EPOCH));
        when(users.get(OTHER)).thenReturn(new UserService.UserRecord(OTHER,"Other",Instant.EPOCH));
    }

    @Test void applicationWiresAndUsesSnakeCaseWithAnExplicitUserPath() throws Exception {
        org.junit.jupiter.api.Assertions.assertInstanceOf(S3AssetStore.class, assetStore);
        String owner="00000000-0000-0000-0000-000000000001";
        when(users.get(owner)).thenReturn(new UserService.UserRecord(owner,"Local user",Instant.parse("2026-09-23T00:00:00Z")));
        mvc.perform(get("/api/v1/users/{user_id}",owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.display_name").value("Local user"))
                .andExpect(jsonPath("$.created_at").value("2026-09-23T00:00:00Z"));
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isBadRequest());
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test void sessionListingUsesTheCanonicalPathOwnerEvenWithAConflictingLegacyHeader() throws Exception {
        when(chats.sessions(OWNER,50)).thenReturn(List.of());
        mvc.perform(get("/api/v1/users/{user_id}/sessions",OWNER.toUpperCase(java.util.Locale.ROOT))
                        .header("X-User-Id",OTHER))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(chats).sessions(OWNER,50);
        verify(users).get(OWNER);
        verify(users,never()).get(OTHER);
    }

    @Test void everyResourceFamilyRejectsInvalidOrUnknownUsersBeforeBusinessAccess() throws Exception {
        String unknown="dddddddd-0000-0000-0000-000000000004";
        when(users.get(unknown)).thenThrow(ApiException.notFound());
        for (String route:List.of("/sessions","/runs/"+RESOURCE,"/tasks/"+RESOURCE,"/assets/"+RESOURCE,
                "/sessions/"+RESOURCE+"/events")) {
            mvc.perform(get("/api/v1/users/invalid"+route)).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/users/"+unknown+route)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/internal/v2/users/"+unknown+"/sessions/"+RESOURCE+"/tasks/"+RESOURCE)).andExpect(status().isNotFound());
        mvc.perform(get("/internal/v1/users/"+unknown+"/assets/"+RESOURCE)).andExpect(status().isNotFound());
        verifyNoInteractions(chats,tasks,assets,streams,sessions);
    }

    @Test void resourceLookupsAndSsePassThePathOwnerToOwnershipChecks() throws Exception {
        when(chats.session(OTHER,RESOURCE)).thenThrow(ApiException.notFound());
        when(chats.run(OTHER,RESOURCE)).thenThrow(ApiException.notFound());
        when(tasks.view(OTHER,RESOURCE)).thenThrow(ApiException.notFound());
        when(tasks.snapshot(OTHER,RESOURCE,RESOURCE)).thenThrow(ApiException.notFound());
        when(assets.get(OTHER,RESOURCE)).thenThrow(ApiException.notFound());
        when(streams.subscribe(OTHER,RESOURCE,0)).thenThrow(ApiException.notFound());
        for (String route:List.of("/sessions/"+RESOURCE,"/runs/"+RESOURCE,
                "/tasks/"+RESOURCE,"/assets/"+RESOURCE,"/assets/"+RESOURCE+"/content","/sessions/"+RESOURCE+"/events"))
            mvc.perform(get("/api/v1/users/"+OTHER+route).header("X-User-Id",OWNER)).andExpect(status().isNotFound());
        mvc.perform(get("/internal/v2/users/"+OTHER+"/sessions/"+RESOURCE+"/tasks/"+RESOURCE)).andExpect(status().isNotFound());
        mvc.perform(get("/internal/v1/users/"+OTHER+"/assets/"+RESOURCE)).andExpect(status().isNotFound());
        verify(chats).session(OTHER,RESOURCE);
        verify(chats).run(OTHER,RESOURCE);
        verify(tasks).view(OTHER,RESOURCE);
        verify(tasks).snapshot(OTHER,RESOURCE,RESOURCE);
        verify(assets,times(3)).get(OTHER,RESOURCE);
        verify(streams).subscribe(OTHER,RESOURCE,0);
    }

    @Test void messageSubmissionAndTaskBatchUseTheOwnerWithoutAHeader() throws Exception {
        mvc.perform(post("/api/v1/users/"+OWNER+"/sessions/"+RESOURCE+"/messages")
                        .contentType("application/json").content("{\"client_request_id\":\""+RESOURCE+"\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}"))
                .andExpect(status().isAccepted());
        verify(sessions).send(eq(OWNER),eq(RESOURCE),eq(RESOURCE),argThat(blocks->((com.lux.backend.chat.ContentBlock.Text) blocks.get(0)).text().equals("Hello")));
        when(tasks.batchViews(OWNER,List.of(RESOURCE))).thenReturn(List.of());
        mvc.perform(post("/api/v1/users/"+OWNER+"/tasks/batch").contentType("application/json")
                        .content("{\"ids\":[\""+RESOURCE+"\"]}"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(tasks).batchViews(OWNER,List.of(RESOURCE));
    }

    @Test void internalTaskCreationReturnsAnImmutableSessionReceipt() throws Exception {
        when(tasks.createSessionTask(eq(OWNER), any())).thenReturn(new ConversationContent.TaskSubmissionReceipt(RESOURCE, true));
        mvc.perform(post("/internal/v2/users/" + OWNER + "/tasks").contentType("application/json")
                        .content("{\"operation_id\":\"" + RESOURCE + "\",\"session_id\":\"" + RESOURCE
                                + "\",\"kind\":\"IMAGE\",\"request\":{}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("task_submission_receipt"))
                .andExpect(jsonPath("$.task_id").value(RESOURCE)).andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.status").doesNotExist());
        verify(tasks).createSessionTask(eq(OWNER), argThat(input -> RESOURCE.equals(input.operationId())
                && RESOURCE.equals(input.sessionId()) && input.kind().equals("IMAGE")));
    }

    @Test void internalTaskQueryAndOperationLookupUseDistinctTypedContracts() throws Exception {
        when(tasks.receiptByOperation(OWNER, RESOURCE)).thenReturn(new ConversationContent.TaskSubmissionReceipt(RESOURCE, true));
        when(tasks.snapshot(OWNER, RESOURCE, RESOURCE)).thenReturn(new ConversationContent.TaskQuerySnapshot(
                RESOURCE, 7, "FAILED", List.of(new ConversationContent.TaskAssetReference(RESOURCE, 2)),
                new ConversationContent.TaskFailure("IMPORT", "IMPORT_SOURCE_HTTP", "HTTP 503")));
        mvc.perform(get("/internal/v2/users/" + OWNER + "/tasks/by-operation/" + RESOURCE))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("task_submission_receipt"));
        mvc.perform(get("/internal/v2/users/" + OWNER + "/sessions/" + RESOURCE + "/tasks/" + RESOURCE))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("task_query_snapshot"))
                .andExpect(jsonPath("$.version").value(7)).andExpect(jsonPath("$.assets[0].source_index").value(2))
                .andExpect(jsonPath("$.failure.stage").value("IMPORT"));
    }

    @Test void sseReconnectUsesLastEventIdAheadOfTheQueryCursor() throws Exception {
        SseEmitter emitter = new SseEmitter(1000L);
        when(streams.subscribe(OWNER, RESOURCE, 9)).thenReturn(emitter);
        mvc.perform(get("/api/v1/users/" + OWNER + "/sessions/" + RESOURCE + "/events?after=2")
                        .header("Last-Event-ID", "9").header("X-User-Id", OTHER))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        verify(streams).subscribe(OWNER, RESOURCE, 9);
        emitter.complete();
    }

    @Test void legacyUnscopedRoutesAreNoLongerAvailable() throws Exception {
        for (String route:List.of("/api/v1/sessions","/api/v1/runs/"+RESOURCE,"/api/v1/tasks/"+RESOURCE,
                "/api/v1/assets/"+RESOURCE,"/internal/v2/tasks/"+RESOURCE,"/internal/v1/assets/"+RESOURCE))
            mvc.perform(get(route).header("X-User-Id",OWNER)).andExpect(status().isNotFound());
        mvc.perform(post("/internal/v1/tasks").header("X-User-Id",OWNER)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(users,chats,tasks,assets,streams,sessions);
    }
}
