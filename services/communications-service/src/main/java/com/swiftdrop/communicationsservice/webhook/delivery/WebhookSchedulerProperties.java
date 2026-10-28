package com.swiftdrop.communicationsservice.webhook.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for durable webhook retry scheduling and background worker execution.
 */
@Component
@ConfigurationProperties(prefix = "swiftdrop.webhooks.scheduler")
public class WebhookSchedulerProperties {

    /**
     * Whether the background delivery scheduler is enabled.
     */
    private boolean enabled = true;

    /**
     * Polling interval in milliseconds for discovering and leasing due webhook deliveries.
     */
    private long pollIntervalMs = 5000L;

    /**
     * Interval in milliseconds for recovering stalled/crashed PROCESSING jobs.
     */
    private long recoveryIntervalMs = 60000L;

    /**
     * Lease timeout in seconds before a PROCESSING delivery job is considered stalled/abandoned.
     */
    private long leaseTimeoutSeconds = 300L; // 5 minutes

    /**
     * Maximum batch size of due deliveries to process per polling cycle.
     */
    private int batchSize = 50;

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
}
