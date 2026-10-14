/**
 * 消息基础设施：通过 Outbox 与 Inbox 把数据库业务事务连接到 Kafka。
 *
 * 出站：业务事务保存 Outbox → OutboxPublisher 发 Kafka → 记录送达或安排重试。
 * 入站：KafkaInbound → InboxProcessor 接收入库 → 在同一事务中应用业务事件并标记 APPLIED。
 * 重复投递仍可能发生；事件身份和业务序号共同保证可恢复处理。BackgroundWorkers 定时推进恢复工作。
 */
package com.lux.backend.infrastructure;
