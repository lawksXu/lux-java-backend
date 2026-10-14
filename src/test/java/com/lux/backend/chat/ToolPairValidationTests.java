package com.lux.backend.chat;

import com.lux.backend.asset.AssetRepository;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.infrastructure.IncomingEvent.*;
import com.lux.backend.task.ConnectorRequest;
import com.lux.backend.task.TaskRecord;
import com.lux.backend.task.TaskRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.lux.backend.chat.ConversationContent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 乱序调用配对后只能指向该原始调用操作受理的 Task，不能借用同 Session 的另一个任务。 */
class ToolPairValidationTests {
    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String SESSION = "00000000-0000-0000-0000-000000000002";
    private static final String TASK = "00000000-0000-0000-0000-000000000003";
    private static final String CALL_ITEM = "00000000-0000-0000-0000-000000000004";
    private final ItemJpaRepository items = mock(ItemJpaRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final JsonCodec json = new JsonCodec(JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());
    private final ChatRepository chats = new ChatRepository(mock(SessionJpaRepository.class), items,
        mock(RunJpaRepository.class), mock(TurnJpaRepository.class), mock(RunEventJpaRepository.class),
        mock(SessionEventJpaRepository.class), mock(EntityManager.class), json,
        new ContentValidator(mock(AssetRepository.class), tasks));

    @Test
    void aLateSubmissionCallCannotPairWithAReceiptFromAnotherOperation() {
        arrangeReceipt("another-operation");
        assertThatThrownBy(() -> chats.validateItemSnapshot(OWNER, SESSION, call()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLateSubmissionCallPairsWithItsOriginalOperationRegardlessOfTaskProgress() {
        arrangeReceipt(CALL_ITEM);
        assertThatNoException().isThrownBy(() -> chats.validateItemSnapshot(OWNER, SESSION, call()));
    }

    private void arrangeReceipt(String operation) {
        TaskRecord task = new TaskRecord(TASK, OWNER, SESSION, operation, "IMAGE", ConnectorRequest.decode(json.read("{}")),
            "SUCCEEDED", 9, 4, List.of(), null, null, null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
        when(tasks.get(OWNER, TASK)).thenReturn(task);
        ItemEntity receipt = new ItemEntity();
        receipt.id = "00000000-0000-0000-0000-000000000005";
        receipt.sessionId = SESSION;
        receipt.runId = "run";
        receipt.kind = "TOOL_RESULT";
        receipt.payload = json.write(new TaskSubmissionReceipt(TASK, true));
        when(items.findByUserIdAndTurnIdAndKindAndCallId(OWNER, "turn", "TOOL_RESULT", "provider-call"))
            .thenReturn(Optional.of(receipt));
    }

    private AgentItemSnapshot call() {
        TurnIdentity turn = new TurnIdentity("turn", 1, new RunIdentity("run", 1));
        SubmitGenerationCallContent payload = new SubmitGenerationCallContent(
            new SubmitGenerationArguments("IMAGE", ConnectorRequest.decode(json.read("{}"))));
        return new AgentItemSnapshot(new AgentItemIdentity(CALL_ITEM, 1, turn), "AGENT", 1,
            "TOOL_CALL", "COMPLETED", payload, Instant.EPOCH, "provider-call", "submit_generation");
    }
}
