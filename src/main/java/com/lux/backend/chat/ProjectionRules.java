package com.lux.backend.chat;

import com.lux.backend.infrastructure.IncomingEvent.*;
import com.lux.backend.chat.ConversationContent.OrderedContent;
import java.util.List;
import java.util.Objects;

/** 不含持久化副作用的来源规则；完整响应先检查全部成员，再允许任何行发生变更。 */
final class ProjectionRules {
    private ProjectionRules() {
    }

    static boolean run(RunSnapshot old, RunSnapshot incoming) {
        if (old == null) {
            return true;
        }
        same(old.run(), incoming.run(), "Run identity");
        boolean apply = revision(old.sourceRevision(), incoming.sourceRevision(), old, incoming);
        if (apply && terminalRun(old.status())) {
            same(old.status(), incoming.status(), "Run terminal status");
            same(old.finalItem(), incoming.finalItem(), "Run final Item selection");
        }
        return apply;
    }

    static boolean turn(TurnSnapshot old, TurnSnapshot incoming) {
        if (old == null) {
            return true;
        }
        same(old.turn(), incoming.turn(), "Turn identity");
        boolean apply = revision(old.sourceRevision(), incoming.sourceRevision(), old, incoming);
        if (apply && terminalTurn(old.status())) {
            same(old.status(), incoming.status(), "Turn terminal status");
        }
        return apply;
    }

    static boolean item(AgentItemSnapshot old, AgentItemSnapshot incoming) {
        if (old == null) {
            return true;
        }
        same(old.item(), incoming.item(), "Item identity");
        same(old.source(), incoming.source(), "Item source");
        same(old.kind(), incoming.kind(), "Item kind");
        same(old.callId(), incoming.callId(), "Item call ID");
        same(old.toolName(), incoming.toolName(), "Item tool name");
        // 临时片段不属于原初始来源快照。这里只构造比较视图，绝不清空实际持久化数组。
        AgentItemSnapshot source = old.status().equals("PARTIAL") ? new AgentItemSnapshot(old.item(), old.source(),
            old.sourceRevision(), old.kind(), old.status(), new OrderedContent(List.of()), old.completedAt(),
            old.callId(), old.toolName()) : old;
        boolean apply = revision(old.sourceRevision(), incoming.sourceRevision(), source, incoming);
        if (apply && !old.status().equals("PARTIAL")) {
            same(old.status(), incoming.status(), "Item terminal status");
            same(old.payload(), incoming.payload(), "Item terminal payload");
            same(old.completedAt(), incoming.completedAt(), "Item terminal time");
        }
        return apply;
    }

    static <T> void same(T a, T b, String field) {
        if (!Objects.equals(a, b)) {
            throw new IllegalArgumentException("Conflicting " + field);
        }
    }

    private static <T> boolean revision(long old, long incoming, T current, T proposed) {
        if (incoming <old) {
            return false;
        }
        if (incoming == old) {
            same(current, proposed, "source revision content");
            return false;
        }
        return true;
    }

    private static boolean terminalRun(String status) {
        return List.of("COMPLETED", "FAILED", "CANCELLED").contains(status);
    }

    private static boolean terminalTurn(String status) {
        return List.of("COMPLETED", "FAILED", "CANCELLED").contains(status);
    }
}
