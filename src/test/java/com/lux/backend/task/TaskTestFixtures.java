package com.lux.backend.task;

import com.lux.backend.common.JsonCodec;
import com.lux.backend.infrastructure.IncomingEvent;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 只模拟数据库事务边界；来源下载和文件存储仍使用真实实现。 */
final class TaskTestFixtures {
    static final String OWNER = UUID.randomUUID().toString();
    static final String SESSION = UUID.randomUUID().toString();
    static final String TASK = UUID.randomUUID().toString();
    static final String OPERATION = UUID.randomUUID().toString();
    static ConnectorRequest request(String json) { return new JsonCodec(JsonMapper.builder().build()).read(json, ConnectorRequest.class); }
    static TaskRecord task(String status, long sequence, List<ResultSource> sources) {
        return new TaskRecord(TASK, OWNER, SESSION, OPERATION, "IMAGE", request("{}"), status, 2, sequence,
                sources, status.equals("FAILED") ? "IMPORT" : null, null, null,
                status.equals("FAILED") || status.equals("SUCCEEDED") ? Instant.EPOCH : null, Instant.EPOCH, Instant.EPOCH);
    }
    static IncomingEvent.Generation success(long sequence, List<ResultSource> sources) {
        return new IncomingEvent.Generation(header(sequence), IncomingEvent.ConnectorExecutionStatus.SUCCEEDED,
                new IncomingEvent.GenerationSuccessOutcome("SUCCEEDED", sources), false);
    }
    static IncomingEvent.Header header(long sequence) {
        return new IncomingEvent.Header(2, UUID.randomUUID().toString(), "GENERATION_EVENT", OWNER, SESSION, TASK, sequence, Instant.EPOCH);
    }
    static TransactionTemplate transactions() {
        return new TransactionTemplate() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                try { return action.doInTransaction(new SimpleTransactionStatus()); }
                finally { TransactionSynchronizationManager.setActualTransactionActive(previous); }
            }
        };
    }
}
