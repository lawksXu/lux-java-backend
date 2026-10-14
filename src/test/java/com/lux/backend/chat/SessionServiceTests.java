package com.lux.backend.chat;

import com.lux.backend.common.HttpIdempotency;
import com.lux.backend.infrastructure.CommandPayload;
import com.lux.backend.infrastructure.OutboxRepository;
import com.lux.backend.infrastructure.Topics;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Task 终态通知复用通用输入管线，不能修改原工具结果或创建 Java Run。 */
class SessionServiceTests {
    private final ChatRepository chats=mock(ChatRepository.class);
    private final OutboxRepository outbox=mock(OutboxRepository.class);
    private final SessionService service=new SessionService(chats,mock(ContentValidator.class),outbox,mock(HttpIdempotency.class));
    @Test void terminalTaskPublishesOneSessionInputWithOnlyTaskIdentity() {
        ChatModels.AcceptedInputItem item=new ChatModels.AcceptedInputItem("input",8,4,"SYSTEM","TASK_EVENT",new ConversationContent.TaskEventContent("task"));
        when(chats.terminalTaskInput("owner","task")).thenReturn(Optional.empty());
        when(chats.insertInput(eq("owner"),eq("session"),anyString(),eq("SYSTEM"),eq("TASK_EVENT"),eq(item.content()),eq("task"))).thenReturn(item);
        assertThat(service.acceptTaskTerminal("owner","session","task").item()).isEqualTo(item);
        verify(outbox).enqueueEnvelope(eq(Topics.AGENT_COMMANDS),eq("owner:session"),argThat(event->event instanceof CommandPayload.InputAccepted accepted
            &&accepted.eventId().equals(item.id())&&accepted.item().content().equals(new ConversationContent.TaskEventContent("task"))));
        verify(chats).event(eq("owner"),eq("session"),eq("ITEM_CREATED"),argThat(change->change instanceof SessionChange.SessionItemNotification notice&&notice.itemSequenceNo()==8));
    }
    @Test void duplicateTerminalTaskRetainsItsOriginalInputAndPublishesNothing() {
        ChatModels.AcceptedInputItem item=new ChatModels.AcceptedInputItem("input",8,4,"SYSTEM","TASK_EVENT",new ConversationContent.TaskEventContent("task"));
        when(chats.terminalTaskInput("owner","task")).thenReturn(Optional.of(item));
        assertThat(service.acceptTaskTerminal("owner","session","task").item()).isEqualTo(item);
        verifyNoInteractions(outbox);verify(chats,never()).insertInput(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any());
        verify(chats,never()).event(anyString(),anyString(),anyString(),any());
    }
}
