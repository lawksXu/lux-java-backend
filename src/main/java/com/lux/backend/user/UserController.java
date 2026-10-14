package com.lux.backend.user;

import com.lux.backend.common.Ids;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户查询 HTTP 入口，先规范化路径 UUID，再通过服务层读取用户快照。 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    /** 用户查询服务，用于确认路径用户存在并取得用户身份。 */
    private final UserService users;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public UserController(UserService users) { this.users = users; }

    /** 校验路径 UUID 并返回用户快照。 */
    @GetMapping("/{user_id}")
    public UserService.UserRecord get(@PathVariable("user_id") String userId) { return users.get(Ids.require(userId)); }
}
