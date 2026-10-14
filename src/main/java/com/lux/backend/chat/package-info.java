/**
 * Session 对话业务：立即受理不可变输入，并将 Python Run/Turn/Item 事实投影给前端。
 * 输入链路：SessionController → SessionService → ChatRepository/OutboxRepository。
 * 输出链路：InboxProcessor → RunEventService → 来源投影与 Session SSE 日志。
 * 输入补齐读取保留的原始 Outbox 信封；Java 不绑定输入消费、不调度或裁决执行生命周期。
 */
package com.lux.backend.chat;
