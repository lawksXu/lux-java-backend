package com.lux.backend;

import com.lux.backend.common.ApiException;
import com.lux.backend.common.DatabaseTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class DatabaseTransactionsTests {
    @Test
    void retriesAConflictAfterRollingBackTheEntireAttempt() {
        RecordingManager manager = new RecordingManager();
        DatabaseTransactions work = new DatabaseTransactions(manager);
        AtomicInteger attempts = new AtomicInteger();
        String result = work.execute(status -> {
            if (attempts.incrementAndGet() == 1) throw conflict();
            return "committed";
        });
        assertThat(result).isEqualTo("committed");
        assertThat(manager.begins).isEqualTo(2);
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isEqualTo(1);
    }

    @Test
    void retriesFailuresDetectedAtCommitWithFreshWork() {
        RecordingManager manager = new RecordingManager();
        manager.failCommits = 1;
        AtomicInteger attempts = new AtomicInteger();
        new DatabaseTransactions(manager).execute(status -> attempts.incrementAndGet());
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(manager.begins).isEqualTo(2);
        assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test
    void retriesAreBoundedAndBusinessErrorsAreNotRetried() {
        RecordingManager manager = new RecordingManager();
        DatabaseTransactions work = new DatabaseTransactions(manager);
        assertThatThrownBy(() -> work.execute(status -> { throw conflict(); }))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(manager.begins).isBetween(2, 10);
        assertThat(manager.rollbacks).isEqualTo(manager.begins);
        int before = manager.begins;
        assertThatThrownBy(() -> work.execute(status -> { throw ApiException.badRequest("Invalid"); }))
                .isInstanceOf(ApiException.class);
        assertThat(manager.begins).isEqualTo(before + 1);
    }

    @Test
    void nestedWorkPropagatesTheConflictToTheOuterTransaction() {
        RecordingManager manager = new RecordingManager();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            AtomicInteger attempts = new AtomicInteger();
            assertThatThrownBy(() -> new DatabaseTransactions(manager).execute(status -> {
                attempts.incrementAndGet();
                throw conflict();
            })).isInstanceOf(OptimisticLockingFailureException.class);
            assertThat(attempts.get()).isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private static OptimisticLockingFailureException conflict() {
        return new OptimisticLockingFailureException("Concurrent update");
    }

    private static class RecordingManager extends AbstractPlatformTransactionManager {
        int begins, commits, rollbacks, failCommits;
        RecordingManager() { setRollbackOnCommitFailure(true); }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { begins++; }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommits-- > 0) throw conflict();
            commits++;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
