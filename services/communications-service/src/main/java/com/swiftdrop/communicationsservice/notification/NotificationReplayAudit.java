package com.swiftdrop.communicationsservice.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable audit record capturing authorized replay operations for terminally failed or exhausted notification jobs.
 * Enforces compliance with Issue #117 and DEC-COM-01.
 */
@Entity
@Table(name = "notification_replays")
public class NotificationReplayAudit {

    @Id
    @Column(name = "replay_id", nullable = false)
    private UUID replayId;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "original_event_id", nullable = false)
    private UUID originalEventId;

    @Column(name = "actor_sub", nullable = false, length = 255)
    private String actorSub;

    @Column(name = "actor_role", nullable = false, length = 100)
    private String actorRole;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "previous_status", nullable = false, length = 30)
    private String previousStatus;

    @Column(name = "decision", nullable = false, length = 30)
    private String decision;

    @Column(name = "replayed_at", nullable = false)
    private Instant replayedAt;

    public NotificationReplayAudit() {
    }

    public NotificationReplayAudit(UUID replayId, UUID notificationId, UUID originalEventId,
                                   String actorSub, String actorRole, String reason,
                                   String previousStatus, String decision, Instant replayedAt) {
        this.replayId = replayId != null ? replayId : UUID.randomUUID();
        this.notificationId = Objects.requireNonNull(notificationId, "notificationId cannot be null");
        this.originalEventId = Objects.requireNonNull(originalEventId, "originalEventId cannot be null");
        this.actorSub = Objects.requireNonNull(actorSub, "actorSub cannot be null");
        this.actorRole = Objects.requireNonNull(actorRole, "actorRole cannot be null");
        this.reason = Objects.requireNonNull(reason, "reason cannot be null");
        this.previousStatus = Objects.requireNonNull(previousStatus, "previousStatus cannot be null");
        this.decision = Objects.requireNonNull(decision, "decision cannot be null");
        this.replayedAt = replayedAt != null ? replayedAt : Instant.now();
    }

    @PrePersist
    public void prePersist() {
        if (this.replayId == null) {
            this.replayId = UUID.randomUUID();
        }
        if (this.replayedAt == null) {
            this.replayedAt = Instant.now();
        }
    }

    public UUID getReplayId() {
        return replayId;
    }

    public void setReplayId(UUID replayId) {
        this.replayId = replayId;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(UUID notificationId) {
        this.notificationId = notificationId;
    }

    public UUID getOriginalEventId() {
        return originalEventId;
    }

    public void setOriginalEventId(UUID originalEventId) {
        this.originalEventId = originalEventId;
    }

    public String getActorSub() {
        return actorSub;
    }

    public void setActorSub(String actorSub) {
        this.actorSub = actorSub;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public void setPreviousStatus(String previousStatus) {
        this.previousStatus = previousStatus;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public Instant getReplayedAt() {
        return replayedAt;
    }

    public void setReplayedAt(Instant replayedAt) {
        this.replayedAt = replayedAt;
    }
}
