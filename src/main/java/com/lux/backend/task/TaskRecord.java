package com.lux.backend.task;

import java.time.Instant;
import java.util.List;

/** Session 拥有的任务快照，保留原请求和固定来源清单；没有执行或供应商 FK。 */
public record TaskRecord(String id, String userId, String sessionId, String operationId,
                         String fileType, ConnectorRequest payload, String status, long version,
                         long providerEventSeq, List<ResultSource> resultSources, String errorStage,
                         String errorCode, String errorMessage, Instant finishedAt,
                         Instant createdAt, Instant updatedAt, String planId) {
    public TaskRecord {
        if (resultSources != null) resultSources = List.copyOf(resultSources);
    }

    /** 保留旧直接任务构造契约；这些任务没有计划关联。 */
    public TaskRecord(String id, String userId, String sessionId, String operationId,
                      String fileType, ConnectorRequest payload, String status, long version,
                      long providerEventSeq, List<ResultSource> resultSources, String errorStage,
                      String errorCode, String errorMessage, Instant finishedAt, Instant createdAt, Instant updatedAt) {
        this(id, userId, sessionId, operationId, fileType, payload, status, version, providerEventSeq, resultSources,
            errorStage, errorCode, errorMessage, finishedAt, createdAt, updatedAt, null);
    }

    /** 验证全部资源后才成功，确认失败也只形成一次终态。 */
    public boolean terminal() { return status.equals("SUCCEEDED") || status.equals("FAILED"); }
}
