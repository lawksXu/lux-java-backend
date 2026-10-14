package com.lux.backend.chat;

import com.lux.backend.common.HttpIdempotency;
import com.lux.backend.common.Ids;
import com.lux.backend.infrastructure.CommandPayload;
import com.lux.backend.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.lux.backend.chat.ChatModels.*;

/**
 * 会话/输入/投影入口；不暴露队列编辑、领取或 Java 执行调度。
 */
@RestController
@RequestMapping("/api/v1/users/{user_id}/sessions")
public class SessionController {
    /**
     * 校验路径用户身份，防止跨所有者访问会话。
     */
    private final UserService users;
    /**
     * 会话输入受理与控制投递边界，持久化成功不代表已执行。
     */
    private final SessionService sessions;
    /**
     * 会话锁、来源投影与持久化通知的统一业务仓储。
     */
    private final ChatRepository chats;
    /**
     * HTTP 请求键与原响应的事务幂等边界。
     */
    private final HttpIdempotency requests;

    public SessionController(
        SessionService sessions,
        ChatRepository chats,
        HttpIdempotency requests,
        UserService users
    ) {
        this.sessions = sessions;
        this.chats = chats;
        this.requests = requests;
        this.users = users;
    }

    public record Title(
        @NotBlank
        @Size(max = 255)
        String title) {
    }

    public record Input(
        @NotBlank String clientRequestId,
        @NotNull List<ContentBlock> content) {
    }

    /**
     * 使用 HTTP 回执幂等创建会话，重复请求返回原始身份。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionRecord create(
        @PathVariable("user_id")
        String user,
        @RequestHeader("Idempotency-Key")
        String key,
        @Valid
        @RequestBody Title input
    ) {
        String owner = owner(user);
        return requests.execute(
            owner, key, "session.create", input, SessionRecord.class, () -> sessions.create(
                owner,
                input.title()
            )
        );
    }

    /**
     * 在当前所有者范围内读取最近会话，限定单次查询大小。
     */
    @GetMapping
    public List<SessionRecord> list(
        @PathVariable("user_id")
        String user,
        @RequestParam(defaultValue = "50")
        @Min(1)
        @Max(100)
        int limit
    ) {
        return chats.sessions(owner(user), limit);
    }

    /**
     * 只读取路径用户拥有的会话。
     */
    @GetMapping("/{id}")
    public SessionRecord get(
        @PathVariable("user_id")
        String user,
        @PathVariable String id
    ) {
        return chats.session(owner(user), Ids.require(id));
    }

    /**
     * 把目标 Session 纳入请求摘要，标题更新和回执一起提交。
     */
    @PatchMapping("/{id}")
    public SessionRecord rename(
        @PathVariable("user_id")
        String user,
        @PathVariable String id,
        @RequestHeader("Idempotency-Key")
        String key,
        @Valid
        @RequestBody Title input
    ) {
        String owner = owner(user);
        return requests.execute(
            owner, key, "session.rename:" + Ids.require(id), input, SessionRecord.class,
            () -> sessions.rename(owner, id, input.title())
        );
    }

    /**
     * 立即受理输入并可靠投递；响应不分配或报告 Java Run。
     */
    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InputAcceptanceResponse send(
        @PathVariable("user_id")
        String user,
        @PathVariable String id,
        @Valid
        @RequestBody Input input
    ) {
        return sessions.send(owner(user), Ids.require(id), input.clientRequestId(), input.content());
    }

    /**
     * 获取同一 MVCC 快照中的分页投影和 SSE 接续水位。
     */
    @GetMapping("/{id}/items")
    public SessionProjectionPage items(
        @PathVariable("user_id")
        String user,
        @PathVariable String id,
        @RequestParam(required = false)
        @Min(1)
        Long before,
        @RequestParam(defaultValue = "50")
        @Min(1)
        @Max(200)
        int limit
    ) {
        return sessions.projectionPage(owner(user), Ids.require(id), before, limit);
    }

    /**
     * 按所有者与 Session 读取单条投影供刷新。
     */
    @GetMapping("/{id}/items/{item}")
    public ItemProjection item(
        @PathVariable("user_id")
        String user,
        @PathVariable String id,
        @PathVariable String item
    ) {
        return chats.item(owner(user), Ids.require(id), Ids.require(item));
    }

    /**
     * 幂等投递 Session 停止控制；执行效果由 Python 确定。
     */
    @PostMapping("/{id}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ControlAcceptanceResponse stop(
        @PathVariable("user_id")
        String user,
        @PathVariable String id,
        @RequestHeader("Idempotency-Key")
        String key
    ) {
        String owner = owner(user);
        return requests.execute(
            owner, key, "session.stop:" + Ids.require(id), new CommandPayload.Empty(),
            ControlAcceptanceResponse.class, () -> sessions.stop(owner, id)
        );
    }

    /**
     * 幂等投递新的唤醒控制，不在 Java 重启或选择 Run。
     */
    @PostMapping("/{id}/continue")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ControlAcceptanceResponse resume(
        @PathVariable("user_id")
        String user,
        @PathVariable String id,
        @RequestHeader("Idempotency-Key")
        String key
    ) {
        String owner = owner(user);
        return requests.execute(
            owner, key, "session.continue:" + Ids.require(id), new CommandPayload.Empty(),
            ControlAcceptanceResponse.class, () -> sessions.resume(owner, id)
        );
    }

    /**
     * 按 Session 来源事件序号读取真实日志，缺口不制造执行状态。
     */
    @GetMapping("/{id}/execution-events")
    public List<RunEvent> events(
        @PathVariable("user_id")
        String user,
        @PathVariable String id,
        @RequestParam(defaultValue = "0")
        @Min(0)
        long after,
        @RequestParam(defaultValue = "100")
        @Min(1)
        @Max(500)
        int limit
    ) {
        String owner = owner(user);
        chats.session(owner, Ids.require(id));
        return chats.executionEvents(owner, id, after, limit);
    }

    private String owner(String id) {
        return users.get(Ids.require(id)).id();
    }
}
