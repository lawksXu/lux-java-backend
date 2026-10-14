package com.lux.backend.chat;

import com.lux.backend.infrastructure.IncomingEvent.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static com.lux.backend.chat.ConversationContent.*;
import static org.assertj.core.api.Assertions.*;

/** 来源字段比较保护实际持久化片段、终态与所有原始调用关联。 */
class ChatRepositoryTests {
    private final RunIdentity run=new RunIdentity("run",1);
    private final TurnIdentity turn=new TurnIdentity("turn",1,run);
    private final AgentItemIdentity item=new AgentItemIdentity("item",1,turn);

    @Test void replayedInitialSnapshotDoesNotTreatTemporaryFragmentsAsSourceChanges() {
        OrderedContent accumulated=new OrderedContent(List.of(new ContentBlock.Text("Hello"),new ContentBlock.Text(" world")));
        AgentItemSnapshot current=snapshot(1,"PARTIAL",accumulated,null);
        AgentItemSnapshot initial=snapshot(1,"PARTIAL",new OrderedContent(List.of()),null);
        assertThat(ProjectionRules.item(current,initial)).isFalse();
        assertThat(accumulated.blocks()).extracting(block->((ContentBlock.Text)block).text()).containsExactly("Hello"," world");
    }
    @Test void terminalPayloadIsImmutableEvenAtAHigherRevision() {
        AgentItemSnapshot completed=snapshot(2,"COMPLETED",new OrderedContent(List.of(new ContentBlock.Text("final"))),Instant.EPOCH);
        AgentItemSnapshot changed=snapshot(3,"COMPLETED",new OrderedContent(List.of(new ContentBlock.Text("different"))),Instant.EPOCH);
        assertThatThrownBy(()->ProjectionRules.item(completed,changed)).hasMessageContaining("terminal payload");
    }
    @Test void oldSnapshotCannotUndoTerminalContent() {
        AgentItemSnapshot completed=snapshot(2,"COMPLETED",new OrderedContent(List.of(new ContentBlock.Text("final"))),Instant.EPOCH);
        assertThat(ProjectionRules.item(completed,snapshot(1,"PARTIAL",new OrderedContent(List.of()),null))).isFalse();
    }
    @Test void equalRevisionWithDifferentSourceContentConflicts() {
        TurnSnapshot a=new TurnSnapshot(turn,1,"RUNNING",Instant.EPOCH,null,null,null);
        TurnSnapshot b=new TurnSnapshot(turn,1,"INTERRUPTED",Instant.EPOCH,null,"UNKNOWN",null);
        assertThatThrownBy(()->ProjectionRules.turn(a,b)).hasMessageContaining("source revision content");
    }
    @Test void terminalRunCannotChangeItsFinalItemSelection() {
        RunSnapshot completed=new RunSnapshot(run,2,"COMPLETED",Instant.EPOCH,Instant.EPOCH,item,null,null);
        RunSnapshot changed=new RunSnapshot(run,3,"COMPLETED",Instant.EPOCH,Instant.EPOCH,new AgentItemIdentity("other",2,turn),null,null);
        assertThatThrownBy(()->ProjectionRules.run(completed,changed)).hasMessageContaining("final Item selection");
    }
    @Test void sourceOrderingIdentityRemainsImmutableAtOldRevisions() {
        AgentItemSnapshot current=snapshot(2,"COMPLETED",new OrderedContent(List.of()),Instant.EPOCH);
        AgentItemSnapshot wrong=new AgentItemSnapshot(new AgentItemIdentity("item",2,turn),"AGENT",1,"MESSAGE","PARTIAL",new OrderedContent(List.of()),null,null,null);
        assertThatThrownBy(()->ProjectionRules.item(current,wrong)).hasMessageContaining("Item identity");
    }
    private AgentItemSnapshot snapshot(long revision,String status,OrderedContent content,Instant completed){return new AgentItemSnapshot(item,"AGENT",revision,"MESSAGE",status,content,completed,null,null);}
}
