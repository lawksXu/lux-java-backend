/**
 * 公共基础组件：请求错误、UUID、JSON 摘要、数据库时间和可重试事务。
 *
 * DatabaseTransactions 负责最外层数据库事务重试；HttpIdempotency 把请求身份、业务动作和响应回执放在同一事务。
 * 幂等表示同一次请求可安全重放，并不意味着可随意重复外部网络调用。
 */
package com.lux.backend.common;
