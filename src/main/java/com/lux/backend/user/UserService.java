package com.lux.backend.user;

import org.springframework.stereotype.Service;

import java.time.Instant;

/** 用户业务查询入口，供控制器确认路径用户存在，并返回适合 HTTP 输出的快照。 */
@Service
public class UserService {
    /** 用户持久化服务，提供查询、初始化和用户级写入协调。 */
    private final UserRepository users;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public UserService(UserRepository users) { this.users = users; }

    /** 通过用户仓储返回快照，用户不存在时由仓储抛出业务 404。 */
    public UserRecord get(String id) {
        return users.get(id);
    }

    /**
     * 对外返回的用户快照，避免直接序列化 Hibernate 受管实体。
     * @param id 用户的唯一 UUID 身份。
     * @param displayName 用户展示名称，与用户唯一身份 ID 分开维护。
     * @param createdAt 记录创建时间，持久化精度为微秒。
     */
    public record UserRecord(String id, String displayName, Instant createdAt) { }
}
