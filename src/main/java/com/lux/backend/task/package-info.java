/**
 * Session 归属的异步生成任务：受理后立即返回固定 task_id，不绑定 Run/Turn/Item。
 *
 * TaskService 保存请求和生成 Outbox，按 connector 执行事实推进业务状态。
 * TaskResultService 在接收 Kafka 的 worker 内同步导入，验证全部不可变 Asset 后才提交 SUCCEEDED。
 * 确认的生成或导入失败直接 FAILED；技术未知由原事件重投恢复，不发起新的业务任务。
 * 终态与唯一 task_id 系统输入、前端通知及 Inbox APPLIED 在同一短事务提交后才允许 Kafka ACK。
 */
package com.lux.backend.task;
