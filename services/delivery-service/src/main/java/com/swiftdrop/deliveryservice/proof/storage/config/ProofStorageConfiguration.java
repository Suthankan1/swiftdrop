package com.swiftdrop.deliveryservice.proof.storage.config;

import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.adapter.S3ProofObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProofStorageProperties.class)
@ConditionalOnProperty(prefix = "swiftdrop.proof-storage", name = "enabled", havingValue = "true")
public class ProofStorageConfiguration {

    @Bean
    S3Client proofStorageS3Client(ProofStorageProperties properties) {
        properties.validateEnabledConfiguration();
        return S3Client.builder()
                .endpointOverride(properties.getEndpoint())
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(properties.getApiCallTimeout())
                        .apiCallAttemptTimeout(properties.getApiCallAttemptTimeout()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Bean
    ProofObjectStorage proofObjectStorage(S3Client proofStorageS3Client, ProofStorageProperties properties) {
        return new S3ProofObjectStorage(proofStorageS3Client, properties);
    }
}
