package com.swiftdrop.communicationsservice.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consumed_events")
public class ConsumedEvent {

    @EmbeddedId
    private ConsumedEventId id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "parcel_id", nullable = false)
    private UUID parcelId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    public ConsumedEvent() {
    }

    public ConsumedEvent(ConsumedEventId id, String eventType, Integer version, UUID parcelId, UUID merchantId, Instant receivedAt) {
        this.id = id;
        this.eventType = eventType;
        this.version = version != null ? version : 1;
        this.parcelId = parcelId;
        this.merchantId = merchantId;
        this.receivedAt = receivedAt != null ? receivedAt : Instant.now();
    }

    public ConsumedEventId getId() {
        return id;
    }

    public void setId(ConsumedEventId id) {
        this.id = id;
    }

    public String getConsumerName() {
        return id != null ? id.getConsumerName() : null;
    }

    public UUID getEventId() {
        return id != null ? id.getEventId() : null;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
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

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
