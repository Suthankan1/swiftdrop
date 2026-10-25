package com.swiftdrop.deliveryservice.proof.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("swiftdrop.proof-storage")
public class ProofStorageProperties {

    private boolean enabled;
    private URI endpoint;
    private String bucket;
    private String region;
    private String accessKey;
    private String secretKey;
    private Duration apiCallTimeout = Duration.ofSeconds(10);
    private Duration apiCallAttemptTimeout = Duration.ofSeconds(3);

    public void validateEnabledConfiguration() {
        if (!enabled) {
            return;
        }
        require(endpoint, "endpoint");
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalStateException("swiftdrop.proof-storage.endpoint must use HTTP or HTTPS");
        }
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalStateException("swiftdrop.proof-storage.endpoint must be an absolute provider endpoint");
        }
        requireText(bucket, "bucket");
        requireText(region, "region");
        requireText(accessKey, "access-key");
        requireText(secretKey, "secret-key");
        requirePositive(apiCallTimeout, "api-call-timeout");
        requirePositive(apiCallAttemptTimeout, "api-call-attempt-timeout");
        if (apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
            throw new IllegalStateException(
                    "swiftdrop.proof-storage.api-call-attempt-timeout must not exceed api-call-timeout");
        }
    }

    public String requiredBucket() {
        requireText(bucket, "bucket");
        return bucket;
    }

    private static void require(Object value, String property) {
        if (value == null) {
            throw new IllegalStateException("Missing swiftdrop.proof-storage." + property);
        }
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing swiftdrop.proof-storage." + property);
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("swiftdrop.proof-storage." + property + " must be positive");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(URI endpoint) {
        this.endpoint = endpoint;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public Duration getApiCallTimeout() {
        return apiCallTimeout;
    }

    public void setApiCallTimeout(Duration apiCallTimeout) {
        this.apiCallTimeout = apiCallTimeout;
    }

    public Duration getApiCallAttemptTimeout() {
        return apiCallAttemptTimeout;
    }

    public void setApiCallAttemptTimeout(Duration apiCallAttemptTimeout) {
        this.apiCallAttemptTimeout = apiCallAttemptTimeout;
    }

    @Override
    public String toString() {
        return "ProofStorageProperties{" +
                "enabled=" + enabled +
                ", endpoint='" + configured(endpoint) + '\'' +
                ", bucket='" + configured(bucket) + '\'' +
                ", region='" + region + '\'' +
                ", accessKey='[REDACTED]'" +
                ", secretKey='[REDACTED]'" +
                ", apiCallTimeout=" + apiCallTimeout +
                ", apiCallAttemptTimeout=" + apiCallAttemptTimeout +
                '}';
    }

    private static String configured(Object value) {
        return value == null ? "[NOT CONFIGURED]" : "[CONFIGURED]";
    }
}
