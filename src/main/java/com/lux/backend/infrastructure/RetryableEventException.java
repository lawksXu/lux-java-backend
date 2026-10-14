package com.lux.backend.infrastructure;

/** 表示当前事件暂时无法应用，例如 Run 缺少前序事件；Inbox 将保留记录并安排重试。 */
public class RetryableEventException extends RuntimeException {
    /** 记录暂时无法应用事件的原因，让 Inbox 走重试路径。 */
    public RetryableEventException(String message) { super(message); }
}
