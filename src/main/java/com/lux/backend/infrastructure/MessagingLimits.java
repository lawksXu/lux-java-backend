package com.lux.backend.infrastructure;

/** 两端 v2 传输预算：信封为完整 UTF-8 JSON，记录还需为 Kafka 协议开销留出余量。 */
public final class MessagingLimits {
    /** 业务信封上限；Python Agent 使用相同限制，不能只放大一个客户端。 */
    public static final int MAX_ENVELOPE_BYTES = 31 * 1024 * 1024;
    /** 新 v2 主题与生产请求的容量。 */
    public static final int MAX_RECORD_BYTES = 32 * 1024 * 1024;
    /** 跨分区获取总预算；每次业务处理仍然只消费一条记录。 */
    public static final int MAX_FETCH_BYTES = 64 * 1024 * 1024;
    private MessagingLimits() {}
}
