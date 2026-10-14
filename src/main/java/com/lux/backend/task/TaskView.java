package com.lux.backend.task;

import com.lux.backend.asset.AssetRecord;
import java.time.Instant;
import java.util.List;

/** 前端卡片只展示验证后的资源，不含 Run/call 绑定或 provider 地址。 */
public record TaskView(String id, String sessionId, String kind, String status, long version,
                       String errorStage, String errorCode, String errorMessage, Instant finishedAt,
                       Instant createdAt, Instant updatedAt, List<AssetRecord> assets, String planId) {
    public TaskView { assets = List.copyOf(assets); }
    /** 历史卡片仍可用原构造方式，planId 只在新计划路径存在。 */
    public TaskView(String id, String sessionId, String kind, String status, long version,
                    String errorStage, String errorCode, String errorMessage, Instant finishedAt,
                    Instant createdAt, Instant updatedAt, List<AssetRecord> assets) {
        this(id, sessionId, kind, status, version, errorStage, errorCode, errorMessage, finishedAt,
            createdAt, updatedAt, assets, null);
    }
}
