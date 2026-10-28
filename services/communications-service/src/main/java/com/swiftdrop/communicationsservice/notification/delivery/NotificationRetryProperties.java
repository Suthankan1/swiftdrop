package com.swiftdrop.communicationsservice.notification.delivery;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Validated retry configuration properties for outbound notifications (Issue #117).
 */
@Component
@ConfigurationProperties(prefix = "swiftdrop.notifications.dispatch")
public class NotificationRetryProperties {

    private int maxAttempts = 5;
    private long baseBackoffSeconds = 5;
    private long maxBackoffSeconds = 300;
    private boolean jitterEnabled = false;
    private double jitterFactor = 0.2;
    private int transportTimeoutMs = 5000;

    @PostConstruct
    public void validate() {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1 (got: " + maxAttempts + ")");
        }
        if (baseBackoffSeconds < 1) {
            throw new IllegalArgumentException("baseBackoffSeconds must be at least 1 (got: " + baseBackoffSeconds + ")");
        }
        if (maxBackoffSeconds < baseBackoffSeconds) {
            throw new IllegalArgumentException("maxBackoffSeconds must be >= baseBackoffSeconds (got max: "
                    + maxBackoffSeconds + ", base: " + baseBackoffSeconds + ")");
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be between 0.0 and 1.0 (got: " + jitterFactor + ")");
        }
        if (transportTimeoutMs < 100) {
            throw new IllegalArgumentException("transportTimeoutMs must be at least 100ms (got: " + transportTimeoutMs + ")");
        }
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBaseBackoffSeconds() {
        return baseBackoffSeconds;
    }

    public void setBaseBackoffSeconds(long baseBackoffSeconds) {
        this.baseBackoffSeconds = baseBackoffSeconds;
    }

    public long getMaxBackoffSeconds() {
        return maxBackoffSeconds;
    }

    public void setMaxBackoffSeconds(long maxBackoffSeconds) {
        this.maxBackoffSeconds = maxBackoffSeconds;
    }

    public boolean isJitterEnabled() {
        return jitterEnabled;
    }

    public void setJitterEnabled(boolean jitterEnabled) {
        this.jitterEnabled = jitterEnabled;
    }

    public double getJitterFactor() {
        return jitterFactor;
    }

    public void setJitterFactor(double jitterFactor) {
        this.jitterFactor = jitterFactor;
    }

    public int getTransportTimeoutMs() {
        return transportTimeoutMs;
    }

    public void setTransportTimeoutMs(int transportTimeoutMs) {
        this.transportTimeoutMs = transportTimeoutMs;
    }
}
