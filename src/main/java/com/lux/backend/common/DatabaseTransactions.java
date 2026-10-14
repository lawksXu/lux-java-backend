package com.lux.backend.common;

import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 默认数据库事务模板，以 READ_COMMITTED 隔离级别执行操作，并重试可恢复的锁冲突。
 * 重试覆盖完整外层事务及提交阶段；回调应仅包含可回滚的数据库操作。
 */
@Component
@Primary
public class DatabaseTransactions extends TransactionTemplate {
    /** 最外层数据库事务最多尝试八次，包含首次执行。 */
    private static final int MAX_ATTEMPTS = 8;

    /** 绑定事务管理器并选择 READ_COMMITTED，作为应用默认事务模板。 */
    public DatabaseTransactions(PlatformTransactionManager manager) {
        super(manager);
        setIsolationLevel(ISOLATION_READ_COMMITTED);
    }

    /** 执行事务并捕获包括提交阶段在内的锁冲突，最多尝试八次；已加入的事务交给外层处理。 */
    @Override
    public <T> T execute(TransactionCallback<T> action) {
        // 已加入外层事务时，回滚标记会影响整个事务；必须由最外层边界重开事务，不能在内层局部重试。
        if (TransactionSynchronizationManager.isActualTransactionActive()) return super.execute(action);
        for (int attempt = 1; ; attempt++) {
            try {
                // 事务提交发生在 super.execute 返回之前，因此提交时才发现的版本冲突也能被本轮 catch 捕获。
                return super.execute(action);
            } catch (RuntimeException failure) {
                if (!retryable(failure) || attempt >= MAX_ATTEMPTS) throw failure;
                try {
                    // 短暂随机退避，减少并发请求在重试时再次同时争用同一行。
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5, Math.min(100, 10L << attempt)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
            }
        }
    }

    /** 沿异常原因链识别乐观锁、悲观锁和锁等待超时，其他业务异常不自动重试。 */
    private boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException
                    || cause instanceof PessimisticLockingFailureException
                    || cause instanceof jakarta.persistence.OptimisticLockException
                    || cause instanceof jakarta.persistence.PessimisticLockException
                    || cause instanceof jakarta.persistence.LockTimeoutException) return true;
        }
        return false;
    }
}
