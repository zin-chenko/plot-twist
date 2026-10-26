package io.plottwist.domain.config;

import io.plottwist.domain.media.application.port.ObjectStorage;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class StorageConfig {
  @Bean(destroyMethod = "close")
  S3Client s3(
      @Value("${storage.endpoint:}") String endpoint,
      @Value("${storage.region:us-east-1}") String region,
      @Value("${storage.access-key:}") String accessKey,
      @Value("${storage.secret-key:}") String secretKey,
      @Value("${storage.path-style:false}") boolean pathStyle,
      @Value("${storage.use-environment-proxy:false}") boolean useProxy) {
    var builder =
        S3Client.builder()
            .region(Region.of(region))
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                    .pathStyleAccessEnabled(pathStyle)
                    .chunkedEncodingEnabled(false)
                    .build())
            .httpClientBuilder(
                ApacheHttpClient.builder()
                    .maxConnections(16)
                    .proxyConfiguration(
                        software.amazon.awssdk.http.apache.ProxyConfiguration.builder()
                            .useEnvironmentVariableValues(useProxy)
                            .useSystemPropertyValues(useProxy)
                            .build())
                    .connectionAcquisitionTimeout(Duration.ofSeconds(1))
                    .connectionTimeout(Duration.ofSeconds(2))
                    .socketTimeout(Duration.ofSeconds(4)))
            .overrideConfiguration(
                config ->
                    config
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(4))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(2).build()));
    if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
    if (!accessKey.isBlank() || !secretKey.isBlank()) {
      if (accessKey.isBlank() || secretKey.isBlank())
        throw new IllegalStateException("Both S3 credential fields are required");
      builder.credentialsProvider(
          StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
    } else builder.credentialsProvider(DefaultCredentialsProvider.create());
    return builder.build();
  }

  @Bean
  HealthIndicator objectStorageHealthIndicator(ObjectStorage storage) {
    return () -> {
      try {
        storage.check();
        return Health.up().build();
      } catch (Exception unavailable) {
        return Health.down().build();
      }
    };
  }
}
