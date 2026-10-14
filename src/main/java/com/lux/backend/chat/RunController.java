package com.lux.backend.chat;

import com.lux.backend.common.Ids;
import com.lux.backend.user.UserService;
import org.springframework.web.bind.annotation.*;

/** Run 是只读来源投影；停止操作按 Session 受理。 */
@RestController
@RequestMapping("/api/v1/users/{user_id}/runs")
public class RunController {
    /** 会话锁、来源投影与持久化通知的统一业务仓储。 */
    private final ChatRepository chats;
    /** 校验路径用户身份，防止跨所有者访问会话。 */
    private final UserService users;
    public RunController(ChatRepository chats, UserService users) {
        this.chats = chats;
        this.users = users;
    }

    /** 只读取 Python Run 来源投影，未知状态不作为执行决定。 */
    @GetMapping("/{id}")
    public ChatModels.RunProjection get(
        @PathVariable("user_id")
        String user,
        @PathVariable String id) {
        return chats.run(users.get(Ids.require(user)).id(), Ids.require(id));
    }
}
