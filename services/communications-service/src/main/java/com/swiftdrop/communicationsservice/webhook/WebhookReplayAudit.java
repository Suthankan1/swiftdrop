package com.swiftdrop.communicationsservice.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable audit record capturing authorized replay events for terminally failed webhook delivery jobs.
 * Enforces compliance with DEC-COM-01 and DEC-COM-04.
 */
@Entity
@Table(name = "webhook_replays")
public class WebhookReplayAudit {

    @Id
    @Column(name = "replay_id", nullable = false)
    private UUID replayId;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "actor_sub", nullable = false, length = 255)
    private String actorSub;

    @Column(name = "actor_role", nullable = false, length = 100)
    private String actorRole;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "previous_status", nullable = false, length = 30)
    private String previousStatus;

    @Column(name = "replayed_at", nullable = false)
    private Instant replayedAt;

    public WebhookReplayAudit() {
    }

    public WebhookReplayAudit(UUID replayId, UUID deliveryId, UUID subscriptionId, UUID merchantId,
                              String actorSub, String actorRole, String reason, String previousStatus,
                              Instant replayedAt) {
        this.replayId = replayId != null ? replayId : UUID.randomUUID();
        this.deliveryId = Objects.requireNonNull(deliveryId, "deliveryId cannot be null");
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId cannot be null");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId cannot be null");
        this.actorSub = Objects.requireNonNull(actorSub, "actorSub cannot be null");
        this.actorRole = Objects.requireNonNull(actorRole, "actorRole cannot be null");
        this.reason = Objects.requireNonNull(reason, "reason cannot be null");
        this.previousStatus = Objects.requireNonNull(previousStatus, "previousStatus cannot be null");
        this.replayedAt = replayedAt != null ? replayedAt : Instant.now();
    }

    public UUID getReplayId() {
        return replayId;
    }

    public void setReplayId(UUID replayId) {
        this.replayId = replayId;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(UUID deliveryId) {
        this.deliveryId = deliveryId;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(UUID subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
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

    public Instant getReplayedAt() {
        return replayedAt;
    }

    public void setReplayedAt(Instant replayedAt) {
        this.replayedAt = replayedAt;
    }
}
