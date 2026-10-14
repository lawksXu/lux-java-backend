package com.lux.backend.chat;

import com.lux.backend.common.ApiException;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.common.Timestamps;
import com.lux.backend.infrastructure.EventProtocol;
import com.lux.backend.infrastructure.IncomingEvent;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static com.lux.backend.chat.ChatModels.*;
import static com.lux.backend.infrastructure.IncomingEvent.*;
import static com.lux.backend.chat.ConversationContent.*;

/**
 * 会话持久化边界：Session 锁保护登记/输入/SSE分配；Python 事实的版本互不阻塞。
 */
@Repository
@Transactional(readOnly = true)
public class ChatRepository {
    /**
     * 会话输入受理与控制投递边界，持久化成功不代表已执行。
     */
    private final SessionJpaRepository sessions;
    /**
     * 输入与输出条目索引，分开登记分页和输入补齐。
     */
    private final ItemJpaRepository items;
    /**
     * Run 身份及独立来源版本投影索引。
     */
    private final RunJpaRepository runs;
    /**
     * 真实父 Run 引用及 Turn 来源版本索引。
     */
    private final TurnJpaRepository turns;
    /**
     * 不可变 Session 执行事实日志，保留乱序片段用于恢复。
     */
    private final RunEventJpaRepository journal;
    /**
     * 前端通知日志；当前读分配与一致性快照读取分开。
     */
    private final SessionEventJpaRepository notifications;
    /**
     * 受管实体及事务 flush/锁定重读边界。
     */
    private final EntityManager entities;
    /**
     * 具名内容的 JSON 持久化适配和原事件规范摘要。
     */
    private final JsonCodec json;
    /**
     * 来源调用配对后的固定业务关联校验，也用于重启恢复时重查。
     */
    private final ContentValidator contentValidator;

    public ChatRepository(
        SessionJpaRepository sessions,
        ItemJpaRepository items,
        RunJpaRepository runs,
        TurnJpaRepository turns,
        RunEventJpaRepository journal,
        SessionEventJpaRepository notifications,
        EntityManager entities,
        JsonCodec json,
        ContentValidator contentValidator
    ) {
        this.sessions = sessions;
        this.items = items;
        this.runs = runs;
        this.turns = turns;
        this.journal = journal;
        this.notifications = notifications;
        this.entities = entities;
        this.json = json;
        this.contentValidator = contentValidator;
    }

    public SessionRecord session(
        String user,
        String id
    ) {
        return view(sessionEntity(user, id));
    }

    /** 聚焦读和写由 Session 同一所有者范围保护，业务 revision 与 ORM 版本分开。 */
    public com.lux.backend.generation.GenerationPlanContract.SessionPlanFocus planFocus(String user, String session) {
        SessionEntity row = sessionEntity(user, session);
        return new com.lux.backend.generation.GenerationPlanContract.SessionPlanFocus(java.util.UUID.fromString(session),
            row.activePlanId == null ? null : java.util.UUID.fromString(row.activePlanId), row.planFocusRevision);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public com.lux.backend.generation.GenerationPlanContract.SessionPlanFocus focusPlan(String user, String session, java.util.UUID plan) {
        SessionEntity row = sessionEntity(user, session);
        String selected = plan == null ? null : plan.toString();
        if (!java.util.Objects.equals(row.activePlanId, selected)) {
            row.activePlanId = selected;
            row.planFocusRevision = Math.addExact(row.planFocusRevision, 1);
            row.updatedAt = com.lux.backend.common.Timestamps.now();
            entities.flush();
        }
        return planFocus(user, session);
    }

    /**
     * 取得并重读 Session 写锁，作为输入、登记和 SSE 共同分配边界。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionRecord sessionForMutation(
        String user,
        String id
    ) {
        entities.flush();
        SessionEntity row = sessionEntity(user, id);
        // 对已归属校验的实体直接加锁刷新；锁定查询会先用旧版本做 Hibernate 校验，尚未刷新就拒绝当前读。
        entities.refresh(row, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return view(row);
    }

    public List<SessionRecord> sessions(
        String user,
        int limit
    ) {
        return sessions
            .findByUserIdOrderByCreatedAtDescIdDesc(user, PageRequest.of(0, limit))
            .stream()
            .map(this::view)
            .toList();
    }

    /**
     * 扫描所有 Session 以发现完全未到达的来源尾部，不调度执行。
     */
    public List<SessionRecord> observationSessions(
        String after,
        int limit
    ) {
        String cursor = after == null ? "" : after;
        return sessions
            .findByIdGreaterThanOrderById(cursor, PageRequest.of(0, Math.min(limit, 100)))
            .stream()
            .map(this::view)
            .toList();
    }

    /**
     * 返回真实已保留序号，用于缺口诊断，不把来源水位当接收进度。
     */
    public List<Long> receivedEventSequences(
        String user,
        String session
    ) {
        return journal
            .findByUserIdAndSessionIdOrderBySequenceNo(user, session)
            .stream()
            .map(row -> row.sequenceNo)
            .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createSession(
        String user,
        String id,
        String title
    ) {
        SessionEntity row = new SessionEntity();
        row.id = id;
        row.userId = user;
        row.title = title;
        row.createdAt = Timestamps.now();
        row.updatedAt = row.createdAt;
        entities.persist(row);
        entities.flush();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rename(
        String user,
        String id,
        String title
    ) {
        SessionEntity row = sessionEntity(user, id);
        row.title = title;
        row.updatedAt = Timestamps.now();
        entities.flush();
    }

    private SessionEntity sessionEntity(
        String user,
        String id
    ) {
        return sessions.findByUserIdAndId(user, id).orElseThrow(ApiException::notFound);
    }

    private long register(
        String user,
        String session
    ) {
        SessionEntity row = sessionEntity(user, session);
        row.nextItemSeq++;
        row.updatedAt = Timestamps.now();
        entities.flush();
        return row.nextItemSeq;
    }

    /**
     * 调用方先持 Session 锁；该输入从创建到补齐永远没有 Run/Turn。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AcceptedInputItem insertInput(
        String user,
        String session,
        String id,
        String source,
        String kind,
        Payload content,
        String taskId
    ) {
        ItemEntity row = new ItemEntity();
        row.id = id;
        row.userId = user;
        row.sessionId = session;
        row.sequenceNo = register(user, session);
        row.source = source;
        row.kind = kind;
        row.inputSequenceNo = items.latestInputForMutation(user, session).orElse(0L) + 1;
        row.status = "COMPLETED";
        row.taskId = taskId;
        row.payload = json.write(content);
        row.completedAt = Timestamps.now();
        row.createdAt = row.completedAt;
        row.updatedAt = row.createdAt;
        entities.persist(row);
        entities.flush();
        return accepted(row);
    }

    public Optional<AcceptedInputItem> terminalTaskInput(
        String user,
        String task
    ) {
        return items.findByUserIdAndTaskId(user, task).map(this::accepted);
    }

    public ItemProjection item(
        String user,
        String session,
        String id
    ) {
        return view(itemEntity(user, session, id));
    }

    public List<ItemProjection> itemsBefore(
        String user,
        String session,
        long before,
        int limit
    ) {
        return items.findByUserIdAndSessionIdAndSequenceNoLessThanOrderBySequenceNoDesc(
            user, session, before,
            PageRequest.of(0, limit)
        ).stream().map(this::view).toList();
    }

    /**
     * 通过独立输入索引补齐，只返回不可变输入条目。
     */
    public List<AcceptedInputItem> inputs(
        String user,
        String session,
        long after,
        long through,
        int limit
    ) {
        return items
            .findByUserIdAndSessionIdAndInputSequenceNoGreaterThanAndInputSequenceNoLessThanEqualOrderByInputSequenceNo(
                user,
                session, after, through, PageRequest.of(0, limit)
            )
            .stream()
            .map(this::accepted)
            .toList();
    }

    public long inputHighWater(
        String user,
        String session
    ) {
        Long high = items.inputHighWater(user, session);
        return high == null ? 0 : high;
    }

    /**
     * 在调用方已有读取快照中观察 SSE 水位，不使用分配当前读。
     */
    public long lastEventSequence(
        String user,
        String session
    ) {
        Long high = notifications.highWater(user, session);
        return high == null ? 0 : high;
    }

    public List<RunProjection> runs(
        String user,
        String session
    ) {
        return runs.findByUserIdAndSessionIdOrderByRunIndex(user, session).stream().map(this::view).toList();
    }

    public List<TurnProjection> turns(
        String user,
        String session
    ) {
        return turns.findByUserIdAndSessionId(user, session).stream().map(this::view).toList();
    }

    public RunProjection run(
        String user,
        String id
    ) {
        return view(runs.findByUserIdAndId(user, id).orElseThrow(ApiException::notFound));
    }

    private ItemEntity itemEntity(
        String user,
        String session,
        String id
    ) {
        return items.findByUserIdAndSessionIdAndId(user, session, id).orElseThrow(ApiException::notFound);
    }

    /**
     * SSE 只从当前已保留日志分配；行锁和插入处于同一业务事务。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long event(
        String user,
        String session,
        String type,
        SessionChange payload
    ) {
        sessionForMutation(user, session);
        long sequence = notifications.latestForMutation(user, session).orElse(0L) + 1;
        SessionEventEntity row = new SessionEventEntity();
        row.userId = user;
        row.sessionId = session;
        row.sequenceNo = sequence;
        row.eventType = type;
        row.payload = json.write(payload);
        row.createdAt = Timestamps.now();
        entities.persist(row);
        entities.flush();
        return sequence;
    }

    public List<SessionEvent> events(
        String user,
        String session,
        long after,
        int limit
    ) {
        return notifications.findByUserIdAndSessionIdAndSequenceNoGreaterThanOrderBySequenceNo(
            user, session,
            after, PageRequest.of(0, limit)
        ).stream().map(row -> new SessionEvent(
            row.sequenceNo, row.eventType,
            SessionChange.read(json, row.eventType, row.payload)
        )).toList();
    }

    public List<RunEvent> executionEvents(
        String user,
        String session,
        long after,
        int limit
    ) {
        return journal.findByUserIdAndSessionIdAndSequenceNoGreaterThanOrderBySequenceNo(
            user, session, after,
            PageRequest.of(0, limit)
        ).stream().map(row -> new RunEvent(
            row.id, row.sequenceNo, row.eventType,
            readJournal(row)
        )).toList();
    }

    /**
     * 原始完整信封用于技术幂等比较，不能以 DTO 再序列化后的 hash 替代它。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void journal(ExecutionFact fact) {
        ExecutionHeader h = fact.header();
        String hash = json.hash(fact.originalEnvelope());
        Optional<RunEventEntity> sameId = journal.findById(h.eventId());
        if (sameId.isPresent()) {
            RunEventEntity old = sameId.get();
            readJournal(old);
            ProjectionRules.same(old.eventHash, hash, "event identity content");
            return;
        }
        if (journal.findBySessionIdAndSequenceNo(h.sessionId(), h.eventSeq()).isPresent()) {
            throw new IllegalArgumentException("Conflicting Session execution sequence");
        }
        if (fact.body() instanceof ItemTextDelta incoming) {
            Optional<RunEventEntity> position =
                journal.findByItemIdAndStreamSeq(incoming.item().id(), incoming.streamSeq());
            if (position.isPresent()) {
                readJournal(position.get());
                // 同一位置只属于原事件 ID；换身份重发即使文本相同也不是可靠重投。
                throw new IllegalArgumentException("Conflicting Item stream event identity");
            }
        }
        if (fact.body() instanceof ModelResponseCompleted incoming) {
            for (RunEventEntity old : journal.findByUserIdAndSessionIdAndEventType(
                h.userId(), h.sessionId(),
                "MODEL_RESPONSE_COMPLETED"
            )) {
                ModelResponseCompleted response = (ModelResponseCompleted) readJournal(old).body();
                if (response.modelExecutionId().equals(incoming.modelExecutionId())) {
                    ProjectionRules.same(
                        response,
                        incoming, "finalized model response"
                    );
                }
            }
        }
        RunEventEntity row = new RunEventEntity();
        row.id = h.eventId();
        row.userId = h.userId();
        row.sessionId = h.sessionId();
        row.runId = sourceRun(fact.body());
        row.itemId = sourceItem(fact.body());
        row.streamSeq = sourceStreamSequence(fact.body());
        row.sequenceNo = h.eventSeq();
        row.eventType = h.eventType();
        row.eventHash = hash;
        row.envelope = json.write(fact.originalEnvelope());
        row.occurredAt = Instant.parse(h.occurredAt());
        row.createdAt = Timestamps.now();
        entities.persist(row);
        entities.flush();
    }

    /**
     * 原文是事实证据，索引只负责定位；每次恢复都核对二者以免错误路由被继续应用。
     */
    private ExecutionFact readJournal(RunEventEntity row) {
        ExecutionFact fact = EventProtocol.agent(json.read(row.envelope));
        ExecutionHeader header = fact.header();
        ProjectionRules.same(row.id, header.eventId(), "journal event ID");
        ProjectionRules.same(row.userId, header.userId(), "journal owner");
        ProjectionRules.same(row.sessionId, header.sessionId(), "journal Session");
        ProjectionRules.same(row.runId, sourceRun(fact.body()), "journal Run routing");
        ProjectionRules.same(row.itemId, sourceItem(fact.body()), "journal Item routing");
        ProjectionRules.same(row.streamSeq, sourceStreamSequence(fact.body()), "journal stream position");
        ProjectionRules.same(row.sequenceNo, header.eventSeq(), "journal Session sequence");
        ProjectionRules.same(row.eventType, header.eventType(), "journal event type");
        ProjectionRules.same(row.occurredAt, Instant.parse(header.occurredAt()), "journal occurrence time");
        ProjectionRules.same(row.eventHash, json.hash(fact.originalEnvelope()), "journal original content");
        return fact;
    }

    private String sourceItem(ExecutionBody body) {
        if (body instanceof AgentItemSnapshot snapshot) {
            return snapshot.item().id();
        }
        if (body instanceof ItemTextDelta delta) {
            return delta.item().id();
        }
        return null;
    }

    private Long sourceStreamSequence(ExecutionBody body) {
        return body instanceof ItemTextDelta delta ? delta.streamSeq() : null;
    }

    private String sourceRun(ExecutionBody body) {
        if (body instanceof RunSnapshot s) {
            return s.run().id();
        }
        if (body instanceof TurnSnapshot s) {
            return s.turn().run().id();
        }
        if (body instanceof AgentItemSnapshot s) {
            return s.item().turn().run().id();
        }
        if (body instanceof ItemTextDelta s) {
            return s.item().turn().run().id();
        }
        return ((ModelResponseCompleted) body).turn().run().id();
    }

    /**
     * 真实身份可先建立内容为空的骨架；冲突不会重分配或移动现有条目。
     */
    private RunEntity ensureRun(
        String user,
        String session,
        RunIdentity identity
    ) {
        validateRunIdentity(user, session, identity);
        Optional<RunEntity> old = runs.findById(identity.id());
        if (old.isPresent()) {
            old.get().lastSeenAt = Timestamps.now();
            return old.get();
        }
        RunEntity row = new RunEntity();
        row.id = identity.id();
        row.userId = user;
        row.sessionId = session;
        row.runIndex = identity.runIndex();
        row.lastSeenAt = Timestamps.now();
        row.createdAt = row.lastSeenAt;
        row.updatedAt = row.createdAt;
        entities.persist(row);
        entities.flush();
        return row;
    }

    private TurnEntity ensureTurn(
        String user,
        String session,
        TurnIdentity identity
    ) {
        ensureRun(user, session, identity.run());
        validateTurnIdentity(user, session, identity);
        Optional<TurnEntity> old = turns.findById(identity.id());
        if (old.isPresent()) {
            return old.get();
        }
        TurnEntity row = new TurnEntity();
        row.id = identity.id();
        row.userId = user;
        row.sessionId = session;
        row.runId = identity.run().id();
        row.turnIndex = identity.turnIndex();
        row.createdAt = Timestamps.now();
        row.updatedAt = row.createdAt;
        entities.persist(row);
        entities.flush();
        return row;
    }

    private ItemEntity ensureItem(
        String user,
        String session,
        AgentItemIdentity identity
    ) {
        ensureTurn(user, session, identity.turn());
        validateItemIdentity(user, session, identity);
        Optional<ItemEntity> old = items.findById(identity.id());
        if (old.isPresent()) {
            return old.get();
        }
        ItemEntity row = new ItemEntity();
        row.id = identity.id();
        row.userId = user;
        row.sessionId = session;
        row.runId = identity.turn().run().id();
        row.turnId = identity.turn().id();
        row.itemIndex = identity.itemIndex();
        row.source = "AGENT";
        row.sequenceNo = register(user, session);
        row.createdAt = Timestamps.now();
        row.updatedAt = row.createdAt;
        entities.persist(row);
        entities.flush();
        return row;
    }

    private void validateRunIdentity(
        String user,
        String session,
        RunIdentity identity
    ) {
        runs.findById(identity.id()).ifPresent(old -> {
            ProjectionRules.same(old.userId, user, "Run owner");
            ProjectionRules.same(old.sessionId, session, "Run Session");
            ProjectionRules.same(old.runIndex, identity.runIndex(), "Run index");
        });
        runs.findBySessionIdAndRunIndex(session, identity.runIndex()).ifPresent(old -> ProjectionRules.same(
            old.id,
            identity.id(), "Run ordering identity"
        ));
    }

    private void validateTurnIdentity(
        String user,
        String session,
        TurnIdentity identity
    ) {
        validateRunIdentity(user, session, identity.run());
        turns.findById(identity.id()).ifPresent(old -> {
            ProjectionRules.same(old.userId, user, "Turn owner");
            ProjectionRules.same(old.sessionId, session, "Turn Session");
            ProjectionRules.same(old.runId, identity.run().id(), "Turn Run");
            ProjectionRules.same(old.turnIndex, identity.turnIndex(), "Turn index");
        });
        turns.findByRunIdAndTurnIndex(identity.run().id(), identity.turnIndex()).ifPresent(old -> ProjectionRules.same(
            old.id,
            identity.id(), "Turn ordering identity"
        ));
    }

    private void validateItemIdentity(
        String user,
        String session,
        AgentItemIdentity identity
    ) {
        validateTurnIdentity(user, session, identity.turn());
        items.findById(identity.id()).ifPresent(old -> {
            ProjectionRules.same(old.userId, user, "Item owner");
            ProjectionRules.same(old.sessionId, session, "Item Session");
            ProjectionRules.same(old.runId, identity.turn().run().id(), "Item Run");
            ProjectionRules.same(old.turnId, identity.turn().id(), "Item Turn");
            ProjectionRules.same(old.itemIndex, identity.itemIndex(), "Item index");
            ProjectionRules.same(old.source, "AGENT", "Item source");
        });
        items
            .findByTurnIdAndItemIndex(identity.turn().id(), identity.itemIndex())
            .ifPresent(old -> ProjectionRules.same(
                old.id,
                identity.id(), "Item ordering identity"
            ));
    }

    /**
     * 按独立来源版本投影 Run，可先建立选中最终 Item 的真实骨架。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyRun(
        String user,
        String session,
        RunSnapshot incoming
    ) {
        validateRunIdentity(user, session, incoming.run());
        RunEntity old = runs.findById(incoming.run().id()).orElse(null);
        boolean apply = ProjectionRules.run(old == null || old.sourceRevision == 0 ? null : source(old), incoming);
        if (incoming.finalItem() != null) {
            validateItemIdentity(user, session, incoming.finalItem());
            validateFinalContent(user, session, incoming.finalItem().id());
        }
        RunEntity row = ensureRun(user, session, incoming.run());
        if (!apply) {
            return;
        }
        if (incoming.finalItem() != null) {
            ensureItem(user, session, incoming.finalItem());
        }
        row.sourceRevision = incoming.sourceRevision();
        row.status = incoming.status();
        row.startedAt = incoming.startedAt();
        row.finishedAt = incoming.finishedAt();
        row.finalItemId = incoming.finalItem() == null ? null : incoming.finalItem().id();
        row.errorCode = incoming.errorCode();
        row.errorMessage = incoming.errorMessage();
        row.updatedAt = Timestamps.now();
        entities.flush();
        event(user, session, "RUN_UPDATED", new SessionChange.RunChanged(row.id));
    }

    /**
     * 按独立来源版本投影 Turn，不依赖父 Run 当前生命周期。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyTurn(
        String user,
        String session,
        TurnSnapshot incoming
    ) {
        validateTurnIdentity(user, session, incoming.turn());
        TurnEntity old = turns.findById(incoming.turn().id()).orElse(null);
        boolean apply = ProjectionRules.turn(
            old == null || old.sourceRevision == 0 ? null : source(old),
            incoming
        );
        TurnEntity row = ensureTurn(user, session, incoming.turn());
        if (!apply) {
            return;
        }
        row.sourceRevision = incoming.sourceRevision();
        row.status = incoming.status();
        row.startedAt = incoming.startedAt();
        row.finishedAt = incoming.finishedAt();
        row.errorCode = incoming.errorCode();
        row.errorMessage = incoming.errorMessage();
        row.updatedAt = Timestamps.now();
        entities.flush();
        event(user, session, "TURN_UPDATED", new SessionChange.TurnChanged(row.id));
    }

    /**
     * 无写入地校验完整成员、原始来源身份与已存在引用。
     */
    public void validateItemSnapshot(
        String user,
        String session,
        AgentItemSnapshot incoming
    ) {
        validateItemIdentity(user, session, incoming.item());
        ItemEntity old = items.findById(incoming.item().id()).orElse(null);
        ProjectionRules.item(old == null || old.sourceRevision == 0 ? null : source(old), incoming);
        if (incoming.callId() != null) {
            items.findByUserIdAndTurnIdAndKindAndCallId(
                user, incoming.item().turn().id(), incoming.kind(),
                incoming.callId()
            ).ifPresent(existing -> ProjectionRules.same(
                existing.id, incoming.item().id(),
                "call Item identity"
            ));
            validateCallPair(user, session, incoming);
        }
        for (RunEntity run : runs.findByUserIdAndSessionIdOrderByRunIndex(user, session)) {
            if (incoming.item().id().equals(run.finalItemId)) {
                requireFinal(
                    incoming.kind(),
                    incoming.status()
                );
            }
        }
    }

    /**
     * 替换来源内容或保留原始流式前缀，与通知原子提交。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyItem(
        String user,
        String session,
        AgentItemSnapshot incoming
    ) {
        validateItemSnapshot(user, session, incoming);
        ItemEntity old = items.findById(incoming.item().id()).orElse(null);
        boolean apply = ProjectionRules.item(
            old == null || old.sourceRevision == 0 ? null : source(old),
            incoming
        );
        ItemEntity row = ensureItem(user, session, incoming.item());
        if (!apply) {
            drain(user, session, row);
            return;
        }
        row.kind = incoming.kind();
        row.sourceRevision = incoming.sourceRevision();
        row.status = incoming.status();
        row.callId = incoming.callId();
        row.toolName = incoming.toolName();
        row.payload = json.write(incoming.payload());
        row.completedAt = incoming.completedAt();
        row.updatedAt = Timestamps.now();
        entities.flush();
        if (row.status.equals("PARTIAL")) {
            event(
                user, session, "ITEM_CREATED", new SessionChange.SessionItemNotification(
                    row.id, row.sequenceNo,
                    view(row)
                )
            );
            drain(user, session, row);
        } else {
            event(
                user, session, "ITEM_CONTENT_REPLACED", new SessionChange.SessionItemNotification(
                    row.id,
                    row.sequenceNo, new SessionChange.ItemContentReplaced(incoming)
                )
            );
        }
    }

    /**
     * 先记录真实身份，再从保留日志应用该 Item 连续片段。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyDelta(
        String user,
        String session,
        ItemTextDelta delta
    ) {
        ItemEntity row = ensureItem(user, session, delta.item());
        drain(user, session, row);
    }

    private List<ItemTextDelta> deltas(
        String user,
        String session,
        String item
    ) {
        List<ItemTextDelta> result = new ArrayList<>();
        for (RunEventEntity row : journal.findByUserIdAndSessionIdAndItemIdAndStreamSeqIsNotNullOrderByStreamSeq(
            user,
            session,
            item
        )) {
            ItemTextDelta delta = (ItemTextDelta) readJournal(row).body();
            result.add(delta);
        }
        return result;
    }

    private void drain(
        String user,
        String session,
        ItemEntity row
    ) {
        if (!"PARTIAL".equals(row.status)) {
            return;
        }
        OrderedContent old = (OrderedContent) content(row);
        List<ContentBlock> blocks = new ArrayList<>(old.blocks());
        TreeMap<Long, ItemTextDelta> buffer = new TreeMap<>();
        for (ItemTextDelta delta : deltas(user, session, row.id)) {
            ProjectionRules.same(identity(row), delta.item(), "stream routing");
            ItemTextDelta previous = buffer.putIfAbsent(delta.streamSeq(), delta);
            if (previous != null) {
                ProjectionRules.same(previous, delta, "stream content");
            }
        }
        while (buffer.containsKey((long) blocks.size() + 1)) {
            ItemTextDelta next = buffer.get((long) blocks.size() + 1);
            blocks.add(new ContentBlock.Text(next.text()));
            row.payload = json.write(new OrderedContent(blocks));
            row.updatedAt = Timestamps.now();
            entities.flush();
            event(
                user, session, "ITEM_TEXT_DELTA", new SessionChange.SessionItemNotification(
                    row.id, row.sequenceNo,
                    next
                )
            );
        }
    }

    /**
     * 恢复已归档片段及未配对调用关系，不制造来源事件或新版本。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void repairSessionProjection(
        String user,
        String session
    ) {
        for (ItemEntity row : items.findByUserIdAndSessionIdAndSequenceNoLessThanOrderBySequenceNoDesc(
            user,
            session, Long.MAX_VALUE, PageRequest.of(0, Integer.MAX_VALUE)
        )) {
            drain(user, session, row);
            if (row.sourceRevision > 0 && row.callId != null) {
                validateCallPair(user, session, source(row));
            }
        }
    }

    private void validateCallPair(
        String user,
        String session,
        AgentItemSnapshot incoming
    ) {
        String opposite = incoming.kind().equals("TOOL_CALL") ? "TOOL_RESULT" : "TOOL_CALL";
        items
            .findByUserIdAndTurnIdAndKindAndCallId(user, incoming.item().turn().id(), opposite, incoming.callId())
            .ifPresent(other -> {
                ProjectionRules.same(other.sessionId, session, "paired call Session");
                ProjectionRules.same(other.runId, incoming.item().turn().run().id(), "paired call Run");
                ToolCallContent call =
                    (ToolCallContent) (incoming.kind().equals("TOOL_CALL") ? incoming.payload() : content(other));
                ToolResultContent result =
                    (ToolResultContent) (incoming.kind().equals("TOOL_RESULT") ? incoming.payload() : content(other));
                if (!(result instanceof ToolError)
                    && ((call instanceof SubmitGenerationCallContent) != (result instanceof TaskSubmissionReceipt))) {
                    throw new IllegalArgumentException("Tool result variant mismatches call");
                }
                if (call instanceof GetTaskCallContent query && result instanceof TaskQuerySnapshot observation) {
                    ProjectionRules.same(query.arguments().taskId(), observation.taskId(), "queried Task identity");
                }
                String callItemId = incoming.kind().equals("TOOL_CALL") ? incoming.item().id() : other.id;
                contentValidator.validateToolPair(user, session, callItemId, call, result);
            });
    }

    private void validateFinalContent(
        String user,
        String session,
        String id
    ) {
        items
            .findByUserIdAndSessionIdAndId(user, session, id)
            .filter(row -> row.sourceRevision > 0)
            .ifPresent(row -> requireFinal(
                row.kind,
                row.status
            ));
    }

    // 部分快照可能早于最终内容到达；它不能让已观察到的 Run 终态被拒绝。
    private void requireFinal(
        String kind,
        String status
    ) {
        if (!"MESSAGE".equals(kind) || "INTERRUPTED".equals(status)) {
            throw new IllegalArgumentException("Run final Item must be a completed Agent message");
        }
    }

    private Payload content(ItemEntity row) {
        return row.payload == null ? null : EventProtocol.payload(row.kind, row.toolName, json.read(row.payload));
    }

    private RunIdentity identity(RunEntity row) {
        return new RunIdentity(row.id, row.runIndex);
    }

    private TurnIdentity identity(TurnEntity row) {
        return new TurnIdentity(row.id, row.turnIndex, identity(runs.findById(row.runId).orElseThrow()));
    }

    private AgentItemIdentity identity(ItemEntity row) {
        return new AgentItemIdentity(row.id, row.itemIndex, identity(turns.findById(row.turnId).orElseThrow()));
    }

    private RunSnapshot source(RunEntity row) {
        return new RunSnapshot(
            identity(row), row.sourceRevision, row.status, row.startedAt, row.finishedAt,
            row.finalItemId == null ? null : identity(items.findById(row.finalItemId).orElseThrow()), row.errorCode,
            row.errorMessage
        );
    }

    private TurnSnapshot source(TurnEntity row) {
        return new TurnSnapshot(
            identity(row), row.sourceRevision, row.status, row.startedAt, row.finishedAt,
            row.errorCode, row.errorMessage
        );
    }

    private AgentItemSnapshot source(ItemEntity row) {
        return new AgentItemSnapshot(
            identity(row), row.source, row.sourceRevision, row.kind, row.status,
            content(row), row.completedAt, row.callId, row.toolName
        );
    }

    private AcceptedInputItem accepted(ItemEntity row) {
        return new AcceptedInputItem(row.id, row.sequenceNo, row.inputSequenceNo, row.source, row.kind, content(row));
    }

    private SessionRecord view(SessionEntity row) {
        return new SessionRecord(
            row.id, row.userId, row.title, row.nextItemSeq, row.version, row.createdAt,
            row.updatedAt
        );
    }

    private ItemProjection view(ItemEntity row) {
        return new ItemProjection(
            row.id, row.userId, row.sessionId, row.sequenceNo, row.source, row.kind,
            row.runId, row.turnId, row.itemIndex, row.inputSequenceNo, row.status, row.callId, row.toolName,
            row.taskId, content(row), row.sourceRevision, row.completedAt, row.version, row.createdAt, row.updatedAt
        );
    }

    private RunProjection view(RunEntity row) {
        return new RunProjection(
            row.id, row.userId, row.sessionId, row.runIndex, row.sourceRevision, row.status,
            row.startedAt, row.finishedAt, row.finalItemId, row.errorCode, row.errorMessage, row.lastSeenAt,
            row.version, row.createdAt, row.updatedAt
        );
    }

    private TurnProjection view(TurnEntity row) {
        return new TurnProjection(
            row.id, row.userId, row.sessionId, row.runId, row.turnIndex, row.sourceRevision,
            row.status, row.startedAt, row.finishedAt, row.errorCode, row.errorMessage, row.version, row.createdAt,
            row.updatedAt
        );
    }
}
