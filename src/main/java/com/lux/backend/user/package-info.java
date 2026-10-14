/**
 * 用户组件：确认本地路径用户存在，并为其他领域提供用户级写入协调。
 *
 * 查询链路：UserController → UserService → UserRepository → UserJpaRepository。
 * UserEntity 负责表映射，UserRecord 负责输出快照；当前路径用户选择不等于登录认证。
 */
package com.lux.backend.user;
