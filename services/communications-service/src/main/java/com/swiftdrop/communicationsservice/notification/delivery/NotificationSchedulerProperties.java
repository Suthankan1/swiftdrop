package com.swiftdrop.communicationsservice.notification.delivery;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Validated scheduler and background worker configuration properties for outbound notifications (Issue #117).
 */
@Component
@ConfigurationProperties(prefix = "swiftdrop.notifications.scheduler")
public class NotificationSchedulerProperties {

    private boolean enabled = true;
    private long pollIntervalMs = 5000;
    private long recoveryIntervalMs = 60000;
    private long leaseTimeoutSeconds = 300;
    private int batchSize = 50;
    private int retentionDays = 30;

    @PostConstruct
    public void validate() {
        if (pollIntervalMs < 100) {
            throw new IllegalArgumentException("pollIntervalMs must be at least 100ms (got: " + pollIntervalMs + ")");
        }
        if (recoveryIntervalMs < 1000) {
            throw new IllegalArgumentException("recoveryIntervalMs must be at least 1000ms (got: " + recoveryIntervalMs + ")");
        }
        if (leaseTimeoutSeconds < 1) {
            throw new IllegalArgumentException("leaseTimeoutSeconds must be at least 1s (got: " + leaseTimeoutSeconds + ")");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000 (got: " + batchSize + ")");
        }
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be at least 1 (got: " + retentionDays + ")");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getRecoveryIntervalMs() {
        return recoveryIntervalMs;
    }

    public void setRecoveryIntervalMs(long recoveryIntervalMs) {
        this.recoveryIntervalMs = recoveryIntervalMs;
    }

    public long getLeaseTimeoutSeconds() {
        return leaseTimeoutSeconds;
    }

    public void setLeaseTimeoutSeconds(long leaseTimeoutSeconds) {
        this.leaseTimeoutSeconds = leaseTimeoutSeconds;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
