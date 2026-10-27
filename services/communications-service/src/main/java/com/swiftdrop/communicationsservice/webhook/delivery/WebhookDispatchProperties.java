package com.swiftdrop.communicationsservice.webhook.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for outbound webhook dispatching and retry tracking.
 */
@Component
@ConfigurationProperties(prefix = "swiftdrop.webhooks.dispatch")
public class WebhookDispatchProperties {

    /**
     * HTTP connection timeout in milliseconds.
     */
    private long connectTimeoutMs = 3000L;

    /**
     * HTTP request/response timeout in milliseconds.
     */
    private long requestTimeoutMs = 5000L;

    /**
     * Maximum retry attempts before marking delivery as EXHAUSTED (per IF-WHK-01 / DEC-COM-01).
     */
    private int maxAttempts = 5;

    /**
     * Base backoff delay in seconds for exponential retry calculation.
     */
    private long baseBackoffSeconds = 5L;

    /**
     * Maximum backoff delay ceiling in seconds.
     */
    private long maxBackoffSeconds = 300L; // 5 minutes

    /**
     * Whether to apply randomized jitter to exponential backoff delays.
     */
    private boolean jitterEnabled = false;

    /**
     * Scaling factor for jitter spread (e.g. 0.2 represents +/- 20% delay variation).
     */
    private double jitterFactor = 0.2;

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
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
}
