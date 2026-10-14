package com.lux.backend.chat;

import com.lux.backend.common.ApiException;
import com.lux.backend.common.HttpIdempotency;
import com.lux.backend.common.Ids;
import com.lux.backend.common.Timestamps;
import com.lux.backend.infrastructure.CommandPayload;
import com.lux.backend.infrastructure.OutboxRepository;
import com.lux.backend.infrastructure.Topics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import static com.lux.backend.chat.ChatModels.*;
import static com.lux.backend.chat.ConversationContent.*;

/** 立即受理不可变 Session 输入；执行决定、输入消费与停止效果全部留在 Python。 */
@Service
public class SessionService {
    /** 会话锁、来源投影与持久化通知的统一业务仓储。 */
    private final ChatRepository chats;
    /** 输入形状及资源归属校验边界。 */
    private final ContentValidator content;
    /** 保存原始稳定事件信封，负责可靠发送和补齐证据。 */
    private final OutboxRepository outbox;
    /** HTTP 请求键与原响应的事务幂等边界。 */
    private final HttpIdempotency requests;
    public SessionService(ChatRepository chats, ContentValidator content, OutboxRepository outbox, HttpIdempotency requests) {
        this.chats = chats;
        this.content = content;
        this.outbox = outbox;
        this.requests = requests;
    }

    /** 创建业务会话，保持所有执行字段由 Python 所有。 */
    @Transactional
    public SessionRecord create(String user, String title) {
        String id = Ids.next();
        chats.createSession(user, id, title);
        return chats.session(user, id);
    }

    /** 锁定会话后更新标题与前端通知。 */
    @Transactional
    public SessionRecord rename(String user, String session, String title) {
        chats.sessionForMutation(user, session);
        chats.rename(user, session, title);
        chats.event(user, session, "SESSION_UPDATED", new SessionChange.TitleChanged(title));
        return chats.session(user, session);
    }

    /** HTTP 回执、输入及可靠投递统一提交；同键重试返回首次响应，无 Java Run 身份。 */
    public InputAcceptanceResponse send(String user, String session, String key, List<ContentBlock> blocks) {
        String id = Ids.require(session), request = Ids.require(key);
        return requests.execute(user, request, "message.send:" + id, new OrderedContent(blocks), InputAcceptanceResponse.class,
            () -> {
            chats.sessionForMutation(user, id);
            content.validate(user, blocks);
            return accept(user, id, "USER", "MESSAGE", new OrderedContent(blocks), null);
        });
    }

    private InputAcceptanceResponse accept(String user, String session, String source, String kind, Payload payload,
        String task) {
        AcceptedInputItem item = chats.insertInput(user, session, Ids.next(), source, kind, payload, task);
        CommandPayload.InputAccepted event = new CommandPayload.InputAccepted(2, item.id(), "INPUT_ACCEPTED",
            Timestamps.now().toString(), user, session, item);
        outbox.enqueueEnvelope(Topics.AGENT_COMMANDS, user + ":" + session, event);
        chats.event(user, session, "ITEM_CREATED", new SessionChange.SessionItemNotification(item.id(), item.sequenceNo(),
            chats.item(user, session, item.id())));
        return new InputAcceptanceResponse(item);
    }

    /** Task 终态事务调用；唯一任务通知与 Task/APPLIED 同步提交，不制造原调用第二个输出。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public InputAcceptanceResponse acceptTaskTerminal(String user, String session, String task) {
        chats.sessionForMutation(user, session);
        return chats.terminalTaskInput(user, task).map(InputAcceptanceResponse::new).orElseGet(() -> accept(user,
            session, "SYSTEM", "TASK_EVENT", new TaskEventContent(task), task));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishTaskInvalidation(String user, String session, String task) {
        chats.sessionForMutation(user, session);
        chats.event(user, session, "TASK_UPDATED", new SessionChange.TaskChanged(task));
    }

    /** 页面及 SSE 水位全部使用同一个非锁定 RR 快照，不能混入当前读。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionProjectionPage projectionPage(String user, String session, Long before, int limit) {
        chats.session(user, session);
        List<ItemProjection> fetched = chats.itemsBefore(user, session, before == null ? Long.MAX_VALUE : before,
            limit + 1);
        boolean more = fetched.size() > limit;
        List<ItemProjection> selected = new ArrayList<>(fetched.subList(0, Math.min(limit, fetched.size())));
        Long next = more ? selected.get(selected.size() - 1).sequenceNo() : null;
        Collections.reverse(selected);
        Set<String> runIds = selected.stream().map(ItemProjection::runId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> turnIds = selected.stream().map(ItemProjection::turnId).filter(Objects::nonNull).collect(Collectors.toSet());
        return new SessionProjectionPage(List.copyOf(selected), chats.runs(user, session).stream().filter(r -> runIds.contains(r.id())).toList(),
            chats.turns(user, session).stream().filter(t -> turnIds.contains(t.id())).toList(), next, chats.lastEventSequence(user,
            session));
    }

    /** 补齐读取保留的原始信封，绝不从最新 DTO 合成新事件或替换旧协议时间。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CommandPayload.InputAccepted> repairInputs(String user, String session, long after, long through,
        int limit) {
        if (after <0 || through <after || limit <1 || limit > 500) {
            throw ApiException.badRequest("Invalid input repair range");
        }
        chats.session(user, session);
        List<AcceptedInputItem> selected = chats.inputs(user, session, after, through, limit);
        List<CommandPayload.InputAccepted> saved = outbox.originalInputEnvelopes(user, session, selected.stream().map(AcceptedInputItem::id).toList());
        List<CommandPayload.InputAccepted> result = new ArrayList<>();
        for (AcceptedInputItem item : selected) {
            CommandPayload.InputAccepted envelope = saved.stream().filter(e -> e.eventId().equals(item.id())).findFirst().orElseThrow(() -> new IllegalStateException("Missing original input envelope"));
            if (!envelope.userId().equals(user)
                || !envelope.sessionId().equals(session)
                || !envelope.item().equals(item)) {
                throw new IllegalStateException("Original input envelope disagrees with immutable Item");
            }
            result.add(envelope);
        }
        return List.copyOf(result);
    }

    /** 受理控制只承诺可靠投递；不在 Java 标记 Run 取消或解析执行对象。 */
    @Transactional
    public ControlAcceptanceResponse stop(String user, String session) {
        chats.sessionForMutation(user, session);
        String id = Ids.next();
        outbox.enqueueEnvelope(Topics.AGENT_COMMANDS, user + ":" + session, new CommandPayload.StopSession(2,
            id, "STOP_SESSION", Timestamps.now().toString(), user, session));
        return new ControlAcceptanceResponse(id);
    }

    /** 只受理可靠继续控制，Python 根据控制顺序决定唤醒效果。 */
    @Transactional
    public ControlAcceptanceResponse resume(String user, String session) {
        chats.sessionForMutation(user, session);
        String id = Ids.next();
        outbox.enqueueEnvelope(Topics.AGENT_COMMANDS, user + ":" + session, new CommandPayload.ContinueSession(2,
            id, "CONTINUE_SESSION", Timestamps.now().toString(), user, session));
        return new ControlAcceptanceResponse(id);
    }
}
