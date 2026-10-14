package com.lux.backend;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

abstract class IntegrationSupport {
    static final String USER = "00000000-0000-0000-0000-000000000001";
    static final String OTHER = "00000000-0000-0000-0000-000000000002";
    // 仅限制本次测试创建的容器，避免小内存 Docker VM 被并行服务耗尽。
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(768L * 1024 * 1024));
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.0.2")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(512L * 1024 * 1024));
    static final GenericContainer<?> STORAGE_SERVER = new GenericContainer<>("chrislusf/seaweedfs:4.47")
            .withCommand("mini", "-dir=/data", "-ip=127.0.0.1", "-ip.bind=0.0.0.0", "-s3.port=8333")
            .withEnv("AWS_ACCESS_KEY_ID", "lux-integration")
            .withEnv("AWS_SECRET_ACCESS_KEY", "isolated-test-storage-secret")
            .withEnv("S3_BUCKET", "lux-assets")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(384L * 1024 * 1024))
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/").forStatusCode(403));
    static final Path STORAGE;

    static {
        try {
            STORAGE = Files.createTempDirectory("lux-integration-assets-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        MYSQL.start();
        KAFKA.start();
        STORAGE_SERVER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("lux.storage.root", STORAGE::toString);
        registry.add("lux.storage.staging-root", () -> STORAGE.resolve("staging").toString());
        registry.add("lux.storage.provider", () -> "s3");
        registry.add("lux.storage.s3.endpoint", () -> "http://" + STORAGE_SERVER.getHost()
                + ":" + STORAGE_SERVER.getMappedPort(8333));
        registry.add("lux.storage.s3.bucket", () -> "lux-assets");
        registry.add("lux.storage.s3.access-key", () -> "lux-integration");
        registry.add("lux.storage.s3.secret-key", () -> "isolated-test-storage-secret");
        registry.add("lux.workers.enabled", () -> false);
        registry.add("lux.local.default-user-id", () -> USER);
    }
}
