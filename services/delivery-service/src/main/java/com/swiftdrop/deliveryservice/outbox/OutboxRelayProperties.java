package com.swiftdrop.deliveryservice.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Delivery Outbox Kafka Relay.
 */
@Component
@ConfigurationProperties(prefix = "swiftdrop.outbox.relay")
public class OutboxRelayProperties {

    private boolean enabled = true;
    private long pollingIntervalMs = 1000L;
    private int batchSize = 50;
    private long claimTimeoutSeconds = 30L;
    private int maxAttempts = 5;
    private long initialBackoffSeconds = 2L;
    private double backoffMultiplier = 2.0;
    private long maxBackoffSeconds = 300L;
    private String topic = "parcel.lifecycle.v1";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollingIntervalMs() {
        return pollingIntervalMs;
    }

    public void setPollingIntervalMs(long pollingIntervalMs) {
        this.pollingIntervalMs = pollingIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getClaimTimeoutSeconds() {
        return claimTimeoutSeconds;
    }

    public void setClaimTimeoutSeconds(long claimTimeoutSeconds) {
        this.claimTimeoutSeconds = claimTimeoutSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getInitialBackoffSeconds() {
        return initialBackoffSeconds;
    }

    public void setInitialBackoffSeconds(long initialBackoffSeconds) {
        this.initialBackoffSeconds = initialBackoffSeconds;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public long getMaxBackoffSeconds() {
        return maxBackoffSeconds;
    }

    public void setMaxBackoffSeconds(long maxBackoffSeconds) {
        this.maxBackoffSeconds = maxBackoffSeconds;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }
}
