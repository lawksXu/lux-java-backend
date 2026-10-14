package com.lux.backend.chat;

import com.lux.backend.infrastructure.IncomingEvent.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只投影 Python 事实；不领取输入、不调度 Run、不以缺失事件推断执行结束。 */
@Service
public class RunEventService {
    /** 会话锁、来源投影与持久化通知的统一业务仓储。 */
    private final ChatRepository chats;
    /** 输入形状及资源归属校验边界。 */
    private final ContentValidator content;
    public RunEventService(ChatRepository chats, ContentValidator content) {
        this.chats = chats;
        this.content = content;
    }

    /** 在接收应用事务内归档原事实，并只更新可独立投影的实体。 */
    @Transactional
    public void applyEvent(ExecutionFact fact) {
        String user = fact.header().userId(), session = fact.header().sessionId();
        chats.sessionForMutation(user, session);
        validateContent(user, session, fact.body());
        if (fact.body() instanceof ModelResponseCompleted response) {
            // 先核验每个成员和引用，再允许 journal/投影写入；事务仍保护任何存储层冲突。
            for (AgentItemSnapshot snapshot : response.items()) {
                chats.validateItemSnapshot(user, session,
                    snapshot);
            }
        }
        chats.journal(fact);
        apply(user, session, fact.body());
    }

    /** 同步原事件及裸来源快照；裸快照不制造日志事件或接收水位。 */
    @Transactional
    public void synchronize(SessionExecutionSync sync) {
        chats.sessionForMutation(sync.userId(), sync.sessionId());
        for (ExecutionFact event : sync.events()) {
            applyEvent(event);
        }
        for (AgentItemSnapshot snapshot : sync.items()) {
            validateContent(sync.userId(), sync.sessionId(), snapshot);
            chats.validateItemSnapshot(sync.userId(), sync.sessionId(), snapshot);
        }
        for (RunSnapshot snapshot : sync.runs()) {
            chats.applyRun(sync.userId(), sync.sessionId(), snapshot);
        }
        for (TurnSnapshot snapshot : sync.turns()) {
            chats.applyTurn(sync.userId(), sync.sessionId(), snapshot);
        }
        for (AgentItemSnapshot snapshot : sync.items()) {
            chats.applyItem(sync.userId(), sync.sessionId(), snapshot);
        }
        chats.repairSessionProjection(sync.userId(), sync.sessionId());
    }

    /** 在会话锁内恢复已有日志对应的内容与引用，不调用模型。 */
    @Transactional
    public void recoverSessionProjection(String user, String session) {
        chats.sessionForMutation(user, session);
        chats.repairSessionProjection(user, session);
    }

    private void apply(String user, String session, ExecutionBody body) {
        if (body instanceof RunSnapshot snapshot) {
            chats.applyRun(user, session, snapshot);
        }
        else if (body instanceof TurnSnapshot snapshot) {
            chats.applyTurn(user, session, snapshot);
        }
        else if (body instanceof AgentItemSnapshot snapshot) {
            chats.applyItem(user, session, snapshot);
        }
        else if (body instanceof ItemTextDelta delta) {
            chats.applyDelta(user, session, delta);
        }
        else for (AgentItemSnapshot snapshot :((ModelResponseCompleted) body).items()) {
            chats.applyItem(user,
                session, snapshot);
        }
    }

    private void validateContent(String user, String session, ExecutionBody body) {
        if (body instanceof AgentItemSnapshot snapshot) {
            content.validateSourcePayload(user, session, snapshot.payload());
        }
        if (body instanceof ModelResponseCompleted response) {
            for (AgentItemSnapshot snapshot : response.items()) {
                validateContent(user, session, snapshot);
            }
        }
    }
}
