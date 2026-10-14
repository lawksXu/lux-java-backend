package com.lux.backend.user;

import com.lux.backend.common.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 用户持久化边界：返回数据快照、协调同一用户的写入并初始化本地用户。 */
@Repository
@Transactional(readOnly = true)
public class UserRepository {
    /** Spring Data 用户实体访问接口，执行数据库查询和版本协调更新。 */
    private final UserJpaRepository users;
    /** JPA 持久化上下文入口，管理实体加载、flush 同步和 refresh 重读。 */
    private final EntityManager entityManager;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    public UserRepository(UserJpaRepository users, EntityManager entityManager) {
        this.users = users;
        this.entityManager = entityManager;
    }

    /** 加载用户实体并转换成 UserRecord，避免暴露持久化层对象。 */
    public UserService.UserRecord get(String id) {
        UserEntity user = users.findById(id).orElseThrow(ApiException::notFound);
        return new UserService.UserRecord(user.id, user.displayName, user.createdAt);
    }

    /** 在已有事务中比较并递增用户版本，随后 refresh，防止使用批量更新前的一级缓存状态。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void coordinate(String id) {
        UserEntity user = users.findById(id).orElseThrow(ApiException::notFound);
        if (users.coordinate(id, user.version) == 0) {
            throw new OptimisticLockingFailureException("User coordination changed concurrently");
        }
        // coordinate 是 JPQL 批量更新，会绕过受管实体字段；refresh 把数据库中的新版本重新读回一级缓存。
        entityManager.refresh(user);
    }

    /** 通过 INSERT ... ON DUPLICATE KEY 保证默认用户只在缺失时创建。 */
    @Transactional
    public void bootstrapLocalUser(String id) {
        entityManager.createNativeQuery("INSERT INTO `user` (id, display_name) VALUES (:id, :name) "
                        + "ON DUPLICATE KEY UPDATE id = id")
                .setParameter("id", id).setParameter("name", "Local user").executeUpdate();
    }
}
