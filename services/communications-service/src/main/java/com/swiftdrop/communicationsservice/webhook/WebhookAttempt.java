package com.swiftdrop.communicationsservice.webhook;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_attempts")
public class WebhookAttempt {

    @Id
    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Column(name = "response_time_ms")
    private Integer responseTimeMs;

    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    public WebhookAttempt() {
    }

    public WebhookAttempt(UUID attemptId, UUID deliveryId, Instant attemptedAt,
                          Integer httpStatusCode, Integer responseTimeMs, String errorSummary) {
        this.attemptId = attemptId != null ? attemptId : UUID.randomUUID();
        this.deliveryId = deliveryId;
        this.attemptedAt = attemptedAt != null ? attemptedAt : Instant.now();
        this.httpStatusCode = httpStatusCode;
        this.responseTimeMs = responseTimeMs;
        this.errorSummary = errorSummary;
    }

    @PrePersist
    public void prePersist() {
        if (this.attemptId == null) {
            this.attemptId = UUID.randomUUID();
        }
        if (this.attemptedAt == null) {
            this.attemptedAt = Instant.now();
        }
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(UUID attemptId) {
        this.attemptId = attemptId;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(UUID deliveryId) {
        this.deliveryId = deliveryId;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public void setAttemptedAt(Instant attemptedAt) {
        this.attemptedAt = attemptedAt;
    }

    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    public void setHttpStatusCode(Integer httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    public Integer getResponseTimeMs() {
        return responseTimeMs;
    }

    public void setResponseTimeMs(Integer responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }
}
