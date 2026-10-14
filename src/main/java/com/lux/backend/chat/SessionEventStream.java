package com.lux.backend.chat;

import com.lux.backend.common.ApiException;
import com.lux.backend.common.JsonCodec;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 连接管理器：定时读取持久化会话事件并向浏览器推送，支持游标重放与心跳。
 * 连接关闭仅停止订阅，不改变 Agent Run 的执行状态。
 */
@Component
public class SessionEventStream {
    /** 会话投影持久化入口，校验归属并读取保留的 SSE 通知。 */
    private final ChatRepository chats;
    /** 统一 JSON 编解码器，负责载荷构建、解析与规范化摘要。 */
    private final JsonCodec json;
    /** SSE 轮询专用的四线程调度池，避免占用业务定时任务线程。 */
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable,"lux-sse"); thread.setDaemon(true); return thread;
    });
    /** 当前打开的 SSE 订阅集合，支持并发读取与删除。 */
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public SessionEventStream(ChatRepository chats,JsonCodec json) { this.chats=chats; this.json=json; }

    /** 校验重放游标与连接数量上限，设置 SSE 连接超时为 30 分钟，并按 250 毫秒固定延迟轮询。 */
    public synchronized SseEmitter subscribe(String user,String session,long after) {
        ChatModels.SessionRecord record=chats.session(user,session);
        if (after<0 || after>chats.lastEventSequence(user,session)) throw ApiException.badRequest("Invalid event cursor");
        if (subscriptions.size()>=128) throw new ApiException(503,"STREAM_LIMIT","Too many event streams");
        SseEmitter emitter=new SseEmitter(1_800_000L);
        Subscription subscription=new Subscription(user,session,after,emitter);
        subscriptions.add(subscription);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(()-> { subscription.close(); emitter.complete(); });
        emitter.onError(error->subscription.close());
        subscription.future=executor.scheduleWithFixedDelay(subscription,0,250,TimeUnit.MILLISECONDS);
        // 连接可能在任务句柄赋值前就已关闭；补做取消，避免漏掉这一并发窗口。
        if (subscription.closed.get()) subscription.future.cancel(false);
        return emitter;
    }
    /** 应用关闭时结束全部订阅并停止线程池，释放连接和后台任务。 */
    @PreDestroy void shutdown() {
        for (Subscription subscription:subscriptions) { subscription.close(); subscription.emitter.complete(); }
        executor.shutdownNow();
    }
    /** 单个 SSE 订阅的运行状态，保存连接、读取游标和可幂等关闭的定时任务。 */
    private final class Subscription implements Runnable {
        /**
         * user 是订阅所属用户 ID，每次轮询继续按该用户校验会话。
         * session 是订阅目标会话 ID。
         */
        final String user,session;
        /** Spring MVC 的 SSE 响应句柄，用于发送事件和结束连接。 */
        final SseEmitter emitter;
        /** 原子关闭标记，保证超时、异常和主动关闭并发时只清理一次。 */
        final AtomicBoolean closed=new AtomicBoolean();
        /** 订阅的定时任务句柄；volatile 让关闭回调可见最新赋值。 */
        volatile ScheduledFuture<?> future;
        /** 此订阅已经成功发送的最大会话事件序号，下一轮从其后读取。 */
        long after;
        /** 最近发送时间的单调时钟读数，用于计算心跳间隔。 */
        long lastSend=System.nanoTime();
        /** 固定本次订阅的用户、会话、起始游标和 SSE 连接。 */
        Subscription(String user,String session,long after,SseEmitter emitter) {
            this.user=user; this.session=session; this.after=after; this.emitter=emitter;
        }
        /** 每次最多推送 100 条后续事件，空闲超过 15 秒发送注释心跳；错误时关闭连接。 */
        @Override public void run() {
            if (closed.get()) return;
            try {
                chats.session(user,session);
                for (ChatModels.SessionEvent event:chats.events(user,session,after,100)) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequenceNo())).name(event.eventType())
                            .data(json.write(event.payload())));
                    // 只有 send 成功后才推进本连接游标；这里使用 Java SSE 序号，不是 Python Session 来源事件序号。
                    after=event.sequenceNo(); lastSend=System.nanoTime();
                }
                if (System.nanoTime()-lastSend>TimeUnit.SECONDS.toNanos(15)) {
                    emitter.send(SseEmitter.event().comment("heartbeat")); lastSend=System.nanoTime();
                }
            } catch (Exception failure) { close(); emitter.completeWithError(failure); }
        }
        /** 以原子标志保证关闭动作只执行一次，移除订阅并取消后续调度。 */
        void close() {
            if (!closed.compareAndSet(false,true)) return;
            subscriptions.remove(this);
            if (future!=null) future.cancel(false);
        }
    }
}
