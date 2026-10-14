package com.lux.backend.asset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** S3 存储装配配置；默认启用 S3，并通过 @Primary 选择 S3AssetStore 作为业务存储实现。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "lux.storage.provider", havingValue = "s3", matchIfMissing = true)
public class S3StorageConfiguration {
    /** 从配置构造同步 S3 客户端，校验端点与凭据，设置路径式寻址和分层超时。 */
    @Bean(destroyMethod = "close")
    S3Client assetS3Client(@Value("${lux.storage.s3.endpoint}") URI endpoint,
                          @Value("${lux.storage.s3.region}") String region,
                          @Value("${lux.storage.s3.access-key}") String accessKey,
                          @Value("${lux.storage.s3.secret-key}") String secretKey) {
        if (accessKey.isBlank() || secretKey.isBlank())
            throw new IllegalArgumentException("LUX_S3_ACCESS_KEY and LUX_S3_SECRET_KEY are required for S3 storage");
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null
                || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())))
            throw new IllegalArgumentException("S3 endpoint must be an HTTP(S) service URL");
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(config -> config.apiCallAttemptTimeout(Duration.ofSeconds(60))
                        .apiCallTimeout(Duration.ofSeconds(60)))
                .build();
    }

    /** 装配默认 S3 资源存储，同时注入旧本地文件读取来源和私有暂存目录。 */
    @Bean
    @Primary
    S3AssetStore s3AssetStore(S3Client assetS3Client, LocalAssetStore legacy,
                            @Value("${lux.storage.s3.bucket}") String bucket,
                            @Value("${lux.storage.staging-root}") String stagingRoot) {
        if (bucket.isBlank()) throw new IllegalArgumentException("S3 bucket is required");
        return new S3AssetStore(assetS3Client, bucket, Path.of(stagingRoot), legacy);
    }
}
