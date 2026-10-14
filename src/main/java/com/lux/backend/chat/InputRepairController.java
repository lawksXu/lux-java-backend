package com.lux.backend.chat;

import com.lux.backend.common.Ids;
import com.lux.backend.infrastructure.CommandPayload;
import com.lux.backend.user.UserService;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Python 缺口补齐只读取保存的原输入，不改变归属、内容或消费状态。 */
@RestController
@RequestMapping("/internal/v2/users/{owner}/sessions/{session}/inputs")
public class InputRepairController {
    /** 会话输入受理与控制投递边界，持久化成功不代表已执行。 */
    private final SessionService sessions;
    /** 校验路径用户身份，防止跨所有者访问会话。 */
    private final UserService users;
    public InputRepairController(SessionService sessions, UserService users) {
        this.sessions = sessions;
        this.users = users;
    }

    /** 返回固定范围内保留的原 InputAccepted，校验所有者和分页范围。 */
    @GetMapping
    public List<CommandPayload.InputAccepted> inputs(
        @PathVariable String owner,
        @PathVariable String session,
        @RequestParam(name = "after_input_sequence_no", defaultValue = "0")
        @Min(0)
        long after,
        @RequestParam(name = "through_input_sequence_no")
        @Min(0)
        long through,
        @RequestParam(defaultValue = "100")
        @Min(1)
        @Max(500)
        int limit) {
        return sessions.repairInputs(users.get(Ids.require(owner)).id(), Ids.require(session), after, through,
            limit);
    }
}
