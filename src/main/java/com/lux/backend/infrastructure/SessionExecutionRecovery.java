package com.lux.backend.infrastructure;

import com.lux.backend.chat.ChatModels;
import com.lux.backend.chat.ChatRepository;
import com.lux.backend.chat.RunEventService;
import com.lux.backend.common.Ids;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.common.Timestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * 只读观察 Python 的会话事件水位与来源快照，修复投影和缺失事实；不会创建、关闭或调度 Run。
 * 本地轮询游标只是查询优化，重启后重新扫描不会丢失执行状态或产生新的模型调用。
 */
@Service
public class SessionExecutionRecovery {
    /** 一次恢复扫描只观察有限数量 Session，避免旧历史阻塞其他后台工作。 */
    private static final int PAGE_SIZE = 20;
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionExecutionRecovery.class);
    private final ChatRepository chats;
    private final RunEventService facts;
    private final OutboxRepository outbox;
    private final TransactionTemplate transactions;
    private final JsonCodec json;
    private final String baseUrl;
    private final HttpClient http;
    private String afterSessionId;
    /** 有界单页读取的独立游标；低序号缺口不能让后续已存在的日志页永远不可见。 */
    private final Map<String, SourcePageCursor> sourcePages = new HashMap<>();

    /** 固定一轮观察的来源上界，不将分页位置当作真实连续接收进度。 */
    private record SourcePageCursor(long after, long through) {}

    /** base-url 为空时禁用主动观察，可靠 Kafka 接收与源 Outbox 仍按各自职责工作。 */
    public SessionExecutionRecovery(ChatRepository chats, RunEventService facts, OutboxRepository outbox,
            TransactionTemplate transactions, JsonCodec json,
            @Value("${lux.agent.base-url:}") String baseUrl) {
        this.chats = chats;
        this.facts = facts;
        this.outbox = outbox;
        this.transactions = transactions;
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        if (!baseUrl.isBlank()) {
            URI uri = URI.create(baseUrl);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("Agent observation base URL must be an HTTP origin/path");
            }
        }
    }

    /** 按稳定 Session 主键循环扫描，覆盖还没有 Run 事实的 Session 和终态后遗漏的尾部。 */
    public void reconcileStale() {
        if (baseUrl.isBlank()) return;
        List<ChatModels.SessionRecord> page = chats.observationSessions(afterSessionId, PAGE_SIZE);
        if (page.isEmpty()) {
            afterSessionId = null;
            page = chats.observationSessions(null, PAGE_SIZE);
        }
        for (ChatModels.SessionRecord session : page) {
            afterSessionId = session.id();
            try {
                observe(session);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                // 网络或读取错误只延后观察，不把 Java 的缺失/超时解释为执行失败。
                LOGGER.warn("Session execution observation deferred for {} ({})",
                        session.id(), failure.getClass().getSimpleName());
            }
        }
    }

    /** HTTP 读取在事务外；原事件与裸来源快照分别按照各自验证规则投影。 */
    private void observe(ChatModels.SessionRecord session) throws java.io.IOException, InterruptedException {
        long prefix = contiguousPrefix(chats.receivedEventSequences(session.userId(), session.id()));
        SourcePageCursor cursor = sourcePages.get(session.id());
        long after = cursor == null ? prefix : cursor.after();
        String fixedBound = cursor == null ? "" : "&through_event_seq=" + cursor.through();
        URI uri = URI.create(baseUrl + "/internal/v2/users/" + session.userId() + "/sessions/"
                + session.id() + "/execution?after_event_seq=" + after + fixedBound + "&limit=100");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new java.io.IOException("Agent observation unavailable");
        IncomingEvent.SessionExecutionSync sync = EventProtocol.sync(json.read(response.body()));
        if (!session.userId().equals(sync.userId()) || !session.id().equals(sync.sessionId())) {
            throw new IllegalArgumentException("Source synchronization ownership differs");
        }
        long throughPage = cursor == null ? sync.eventHighWatermark() : cursor.through();
        if (sync.eventHighWatermark() < throughPage || sync.events().size() > 100
                || sync.events().stream().anyMatch(event -> event.header().eventSeq() <= after
                        || event.header().eventSeq() > throughPage)
                || (sync.nextAfterEventSeq() != null && (sync.nextAfterEventSeq() <= after
                        || sync.nextAfterEventSeq() >= throughPage))) {
            throw new IllegalArgumentException("Source synchronization page differs from requested bounds");
        }
        facts.synchronize(sync);
        facts.recoverSessionProjection(session.userId(), session.id());
        if (sync.nextAfterEventSeq() == null) {
            sourcePages.remove(session.id());
        } else {
            sourcePages.put(session.id(), new SourcePageCursor(sync.nextAfterEventSeq(), throughPage));
        }
        long repaired = contiguousPrefix(chats.receivedEventSequences(session.userId(), session.id()));
        if (repaired < sync.eventHighWatermark()) {
            long through = Math.min(sync.eventHighWatermark(), repaired > Long.MAX_VALUE - 100
                    ? Long.MAX_VALUE : repaired + 100);
            transactions.executeWithoutResult(status -> {
                chats.sessionForMutation(session.userId(), session.id());
                outbox.enqueueEnvelope(Topics.AGENT_COMMANDS, session.userId() + ":" + session.id(),
                        new CommandPayload.ReplaySessionEvents(2, Ids.next(), "REPLAY_SESSION_EVENTS",
                                Timestamps.now().toString(), session.userId(), session.id(), repaired, through, 100));
            });
        }
    }

    /** 只根据真正保留的连续序号推进；最高收到序号不证明中间事实齐全。 */
    static long contiguousPrefix(List<Long> sequences) {
        long prefix = 0;
        for (Long sequence : sequences) {
            if (sequence <= prefix) continue;
            if (prefix == Long.MAX_VALUE || sequence != prefix + 1) break;
            prefix = sequence;
        }
        return prefix;
    }
}
