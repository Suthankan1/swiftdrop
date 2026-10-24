package com.swiftdrop.communicationsservice.webhook;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    @Id
    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "parcel_id", nullable = false)
    private UUID parcelId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "state_version", nullable = false)
    private Integer stateVersion;

    @Column(name = "signed_payload_digest", length = 64)
    private String signedPayloadDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public WebhookDelivery() {
    }

    public WebhookDelivery(UUID deliveryId, UUID subscriptionId, UUID eventId, UUID parcelId,
                           UUID merchantId, Integer stateVersion, String signedPayloadDigest) {
        this.deliveryId = deliveryId != null ? deliveryId : UUID.randomUUID();
        this.subscriptionId = subscriptionId;
        this.eventId = eventId;
        this.parcelId = parcelId;
        this.merchantId = merchantId;
        this.stateVersion = stateVersion != null ? stateVersion : 1;
        this.signedPayloadDigest = signedPayloadDigest;
        this.status = WebhookDeliveryStatus.PENDING;
        this.attemptCount = 0;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    public void prePersist() {
        if (this.deliveryId == null) {
            this.deliveryId = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
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

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public UUID getParcelId() {
        return parcelId;
    }

    public void setParcelId(UUID parcelId) {
        this.parcelId = parcelId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public Integer getStateVersion() {
        return stateVersion;
    }

    public void setStateVersion(Integer stateVersion) {
        this.stateVersion = stateVersion;
    }

    public String getSignedPayloadDigest() {
        return signedPayloadDigest;
    }

    public void setSignedPayloadDigest(String signedPayloadDigest) {
        this.signedPayloadDigest = signedPayloadDigest;
    }

    public WebhookDeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(WebhookDeliveryStatus status) {
        this.status = status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
