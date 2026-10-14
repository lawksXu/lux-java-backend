package com.lux.backend.user;

import com.lux.backend.common.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 本地启动初始化器，在配置允许时插入默认用户；已有用户信息保持原样。 */
@Component
@ConditionalOnProperty(name = "lux.local.bootstrap-user", havingValue = "true", matchIfMissing = true)
public class LocalUserBootstrap implements ApplicationRunner {
    /** 用户持久化服务，提供查询、初始化和用户级写入协调。 */
    private final UserRepository users;
    /** 配置指定的默认本地用户 UUID，构造时完成规范化。 */
    private final String userId;

    /** 注入用户仓储并校验默认用户 UUID，及早发现配置错误。 */
    public LocalUserBootstrap(UserRepository users, @Value("${lux.local.default-user-id:00000000-0000-0000-0000-000000000001}") String userId) {
        this.users = users;
        this.userId = Ids.require(userId);
    }

    /** 应用启动完成后尝试插入配置的默认用户，重复启动不会覆盖已有资料。 */
    @Override
    public void run(ApplicationArguments arguments) {
        users.bootstrapLocalUser(userId);
    }
}
