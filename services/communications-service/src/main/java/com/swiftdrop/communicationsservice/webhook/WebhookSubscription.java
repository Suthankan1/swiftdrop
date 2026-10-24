package com.swiftdrop.communicationsservice.webhook;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_subscriptions")
public class WebhookSubscription {

    @Id
    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "https_url", nullable = false, length = 1000)
    private String httpsUrl;

    @Column(name = "allowed_event_types", nullable = false, length = 255)
    private String allowedEventTypes;

    @Column(name = "secret_ref", nullable = false, length = 255)
    private String secretRef;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public WebhookSubscription() {
    }

    public WebhookSubscription(UUID subscriptionId, UUID merchantId, String httpsUrl, String allowedEventTypes, String secretRef) {
        this.subscriptionId = subscriptionId != null ? subscriptionId : UUID.randomUUID();
        this.merchantId = merchantId;
        this.httpsUrl = httpsUrl;
        this.allowedEventTypes = allowedEventTypes;
        this.secretRef = secretRef;
        this.active = true;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    public void prePersist() {
        if (this.subscriptionId == null) {
            this.subscriptionId = UUID.randomUUID();
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

    public String getHttpsUrl() {
        return httpsUrl;
    }

    public void setHttpsUrl(String httpsUrl) {
        this.httpsUrl = httpsUrl;
    }

    public String getAllowedEventTypes() {
        return allowedEventTypes;
    }

    public void setAllowedEventTypes(String allowedEventTypes) {
        this.allowedEventTypes = allowedEventTypes;
    }

    public String getSecretRef() {
        return secretRef;
    }

    public void setSecretRef(String secretRef) {
        this.secretRef = secretRef;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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
