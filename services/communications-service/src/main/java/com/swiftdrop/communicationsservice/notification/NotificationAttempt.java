package com.swiftdrop.communicationsservice.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable audit record capturing individual outbound notification delivery attempts,
 * failure classifications, and sanitized provider outcomes (Issue #117).
 *
 * Invariant:
 * Zero recipient email addresses, phone numbers, credentials, or raw vendor payloads
 * are stored in notification_attempts.
 */
@Entity
@Table(name = "notification_attempts")
public class NotificationAttempt {

    @Id
    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 50)
    private NotificationChannel channel;

    @Column(name = "outcome", nullable = false, length = 30)
    private String outcome;

    @Column(name = "failure_category", length = 50)
    private String failureCategory;

    @Column(name = "safe_error_code", length = 100)
    private String safeErrorCode;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public NotificationAttempt() {
    }

    public NotificationAttempt(UUID attemptId, UUID notificationId, Integer attemptNumber,
                               NotificationChannel channel, String outcome, String failureCategory,
                               String safeErrorCode, Integer durationMs, Instant attemptedAt,
                               Instant completedAt) {
        this.attemptId = attemptId != null ? attemptId : UUID.randomUUID();
        this.notificationId = Objects.requireNonNull(notificationId, "notificationId cannot be null");
        this.attemptNumber = Objects.requireNonNull(attemptNumber, "attemptNumber cannot be null");
        this.channel = Objects.requireNonNull(channel, "channel cannot be null");
        this.outcome = Objects.requireNonNull(outcome, "outcome cannot be null");
        this.failureCategory = failureCategory;
        this.safeErrorCode = safeErrorCode;
        this.durationMs = durationMs;
        this.attemptedAt = attemptedAt != null ? attemptedAt : Instant.now();
        this.completedAt = completedAt;
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

    public UUID getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(UUID notificationId) {
        this.notificationId = notificationId;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public void setChannel(NotificationChannel channel) {
        this.channel = channel;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
    }

    public String getSafeErrorCode() {
        return safeErrorCode;
    }

    public void setSafeErrorCode(String safeErrorCode) {
        this.safeErrorCode = safeErrorCode;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public void setAttemptedAt(Instant attemptedAt) {
        this.attemptedAt = attemptedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
