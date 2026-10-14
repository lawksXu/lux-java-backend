/**
 * Lux Java 业务后端总览。
 *
 * HTTP 控制器接收用户请求，业务服务维护数据库状态，仓储封装 JPA 实体与查询。
 * Java 保存业务状态和前端展示数据；Python Agent 维护模型上下文并执行 Agent，连接器调用供应商。
 * 建议阅读顺序：user 用户查询 → chat 消息入队与 Run → infrastructure 可靠投递 → task 异步任务 → asset 结果文件。
 */
package com.lux.backend;
