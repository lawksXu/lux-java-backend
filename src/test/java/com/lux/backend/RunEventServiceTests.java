package com.lux.backend;

import com.lux.backend.chat.*;
import com.lux.backend.infrastructure.IncomingEvent.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 业务事务在完整响应所有成员检查通过之前不能写入任一成员。 */
class RunEventServiceTests {
    private final ChatRepository chats=mock(ChatRepository.class);
    private final ContentValidator validator=mock(ContentValidator.class);
    private final RunEventService service=new RunEventService(chats,validator);
    private final RunIdentity run=new RunIdentity("run",1);
    private final TurnIdentity turn=new TurnIdentity("turn",1,run);
    @Test void oneConflictingResponseMemberPreventsEveryProjectionMutation() {
        AgentItemSnapshot a=message("a",1),b=message("b",2);
        ModelResponseCompleted response=new ModelResponseCompleted("model",turn,List.of(a,b));
        ExecutionFact fact=new ExecutionFact(new ExecutionHeader(2,"event","MODEL_RESPONSE_COMPLETED","2026-10-12T00:00:00Z","owner","session",20),response,null);
        doThrow(new IllegalArgumentException("Conflicting content")).when(chats).validateItemSnapshot("owner","session",b);
        assertThatThrownBy(()->service.applyEvent(fact)).hasMessageContaining("Conflicting content");
        verify(chats,never()).journal(any());verify(chats,never()).applyItem(anyString(),anyString(),any());
    }
    @Test void aLateItemHasNoParentRunActiveOrContiguousEventGate() {
        AgentItemSnapshot snapshot=message("late",2);
        ExecutionFact fact=new ExecutionFact(new ExecutionHeader(2,"event","AGENT_ITEM_SNAPSHOT","2026-10-12T00:00:00Z","owner","session",90),snapshot,null);
        service.applyEvent(fact);
        verify(chats).journal(fact);verify(chats).applyItem("owner","session",snapshot);
    }
    private AgentItemSnapshot message(String id,long index){return new AgentItemSnapshot(new AgentItemIdentity(id,index,turn),"AGENT",2,"MESSAGE","COMPLETED",new ConversationContent.OrderedContent(List.of(new ContentBlock.Text("done"))),java.time.Instant.EPOCH,null,null);}
}
