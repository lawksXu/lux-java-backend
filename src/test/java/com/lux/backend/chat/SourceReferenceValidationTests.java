package com.lux.backend.chat;

import com.lux.backend.asset.AssetRepository;
import com.lux.backend.common.ApiException;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.infrastructure.IncomingEvent.*;
import com.lux.backend.task.ConnectorRequest;
import com.lux.backend.task.TaskRecord;
import com.lux.backend.task.TaskRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static com.lux.backend.chat.ConversationContent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 校验真实业务引用，但不能用 Task 的新状态否认已归档的历史工具结果。 */
class SourceReferenceValidationTests {
    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String SESSION = "00000000-0000-0000-0000-000000000002";
    private static final String TASK = "00000000-0000-0000-0000-000000000003";
    private static final String ASSET = "00000000-0000-0000-0000-000000000004";
    private final AssetRepository assets = mock(AssetRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final ChatRepository chats = mock(ChatRepository.class);
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final RunEventService service = new RunEventService(chats, new ContentValidator(assets, tasks));

    @Test
    void aReceiptCannotReferenceATaskFromAnotherSessionEvenBeforeItsCallArrives() {
        when(tasks.get(OWNER, TASK)).thenReturn(task("different-session"));
        assertThatThrownBy(() -> apply("TOOL_RESULT", new TaskSubmissionReceipt(TASK, true)))
            .isInstanceOf(IllegalArgumentException.class);
        verify(chats, never()).journal(any());
    }

    @Test
    void aReceiptCannotReferenceAnotherOwnersTask() {
        when(tasks.get(OWNER, TASK)).thenThrow(ApiException.notFound());
        assertThatThrownBy(() -> apply("TOOL_RESULT", new TaskSubmissionReceipt(TASK, true)))
            .isInstanceOf(ApiException.class);
        verify(chats, never()).journal(any());
    }

    @Test
    void aHistoricalQueryCannotRelabelAnAssetsOriginalSourceIndex() {
        when(tasks.get(OWNER, TASK)).thenReturn(task(SESSION));
        when(assets.taskReferences(OWNER, TASK)).thenReturn(List.of(new TaskAssetReference(ASSET, 3)));
        TaskQuerySnapshot snapshot = new TaskQuerySnapshot(TASK, 1, "IMPORTING",
            List.of(new TaskAssetReference(ASSET, 0)), null);
        assertThatThrownBy(() -> apply("TOOL_RESULT", snapshot)).isInstanceOf(IllegalArgumentException.class);
        verify(chats, never()).journal(any());
    }

    @Test
    void aHistoricalSnapshotNeedNotMatchTheTasksNewStatusVersionOrLaterAssets() {
        when(tasks.get(OWNER, TASK)).thenReturn(task(SESSION));
        when(assets.taskReferences(OWNER, TASK)).thenReturn(List.of(new TaskAssetReference(ASSET, 3),
            new TaskAssetReference("00000000-0000-0000-0000-000000000005", 4)));
        TaskQuerySnapshot snapshot = new TaskQuerySnapshot(TASK, 1, "IMPORTING",
            List.of(new TaskAssetReference(ASSET, 3)), null);
        assertThatNoException().isThrownBy(() -> apply("TOOL_RESULT", snapshot));
        verify(chats).journal(any());
    }

    @Test
    void submissionCallsCannotArchiveAnotherOwnersAssetReference() {
        when(assets.get(OWNER, ASSET)).thenThrow(ApiException.notFound());
        ConnectorRequest request = ConnectorRequest.decode(json.read("{\"asset_id\":\"" + ASSET + "\"}"));
        SubmitGenerationCallContent payload = new SubmitGenerationCallContent(new SubmitGenerationArguments("IMAGE", request));
        assertThatThrownBy(() -> apply("TOOL_CALL", payload)).isInstanceOf(ApiException.class);
        verify(chats, never()).journal(any());
    }

    private void apply(String kind, Payload payload) {
        TurnIdentity turn = new TurnIdentity("turn", 1, new RunIdentity("run", 1));
        AgentItemSnapshot snapshot = new AgentItemSnapshot(new AgentItemIdentity("item", 1, turn),
            "AGENT", 1, kind, "COMPLETED", payload, Instant.EPOCH, "original-call",
            kind.equals("TOOL_CALL") ? "submit_generation" : null);
        ExecutionFact fact = new ExecutionFact(new ExecutionHeader(2, "event", "AGENT_ITEM_SNAPSHOT",
            "2026-10-12T00:00:00Z", OWNER, SESSION, 1), snapshot, null);
        service.applyEvent(fact);
    }

    private TaskRecord task(String session) {
        return new TaskRecord(TASK, OWNER, session, "operation", "IMAGE", ConnectorRequest.decode(json.read("{}")),
            "SUCCEEDED", 9, 4, List.of(), null, null, null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    }
}
