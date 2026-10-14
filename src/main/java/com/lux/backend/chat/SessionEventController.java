package com.lux.backend.chat;

import com.lux.backend.common.Ids;
import com.lux.backend.user.UserService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE 订阅入口，支持首次指定 after，以及重连时通过 Last-Event-ID 重放未读通知。 */
@RestController
@RequestMapping("/api/v1/users/{user_id}/sessions/{sessionId}/events")
public class SessionEventController {
    /** 用户查询服务，用于确认路径用户存在并取得用户身份。 */
    private final UserService users;
    /** SSE 订阅管理器，负责连接、游标重放与心跳。 */
    private final SessionEventStream streams;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public SessionEventController(
        SessionEventStream streams,
        UserService users
    ) {
        this.users = users;
        this.streams = streams;
    }

    /** 确认用户与会话后建立订阅；重连头 Last-Event-ID 优先于查询参数 after。 */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
        @PathVariable("user_id") String userId,
        @PathVariable String sessionId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEvent,
        @RequestParam(defaultValue = "0") long after
    ) {
        String owner = users.get(Ids.require(userId)).id();
        return streams.subscribe(owner, Ids.require(sessionId), lastEvent == null ? after : Long.parseLong(lastEvent));
    }
}
