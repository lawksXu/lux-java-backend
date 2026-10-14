package com.lux.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 启动入口；从 com.lux.backend 包向下扫描组件，装配 Web、数据库和消息处理能力。 */
@SpringBootApplication
public class LuxBackendApplication {

    /** 启动 Spring 容器与内嵌 Web 服务，并传入命令行配置参数。 */
    public static void main(String[] args) {
        SpringApplication.run(LuxBackendApplication.class, args);
    }
}
