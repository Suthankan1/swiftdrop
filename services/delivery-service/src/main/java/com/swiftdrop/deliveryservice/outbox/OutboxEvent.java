package com.swiftdrop.deliveryservice.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional Outbox Entity mapping to delivery_db.outbox_events.
 *
 * Implements DEC-EVT-01..04 for reliable asynchronous Kafka publication.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 32, updatable = false)
    private String aggregateType = "PARCEL";

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_version", nullable = false, updatable = false)
    private Long aggregateVersion;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType = "parcel.status.changed.v1";

    @Column(name = "schema_version", nullable = false, length = 16, updatable = false)
    private String schemaVersion = "1.0.0";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic = "parcel.lifecycle.v1";

    @Column(name = "partition_key", nullable = false, length = 64)
    private String partitionKey;

    public OutboxEvent() {
    }

    public OutboxEvent(UUID eventId,
                       String aggregateType,
                       UUID aggregateId,
                       Long aggregateVersion,
                       String eventType,
                       String schemaVersion,
                       String payload,
                       OutboxStatus status,
                       Instant createdAt,
                       int attemptCount,
                       Instant lastAttemptAt,
                       Instant nextAttemptAt,
                       String lastError,
                       Instant acknowledgedAt,
                       String claimedBy,
                       Instant claimExpiresAt,
                       String topic,
                       String partitionKey) {
        this.eventId = Objects.requireNonNull(eventId, "eventId cannot be null");
        this.aggregateType = aggregateType != null ? aggregateType : "PARCEL";
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId cannot be null");
        this.aggregateVersion = Objects.requireNonNull(aggregateVersion, "aggregateVersion cannot be null");
        this.eventType = eventType != null ? eventType : "parcel.status.changed.v1";
        this.schemaVersion = schemaVersion != null ? schemaVersion : "1.0.0";
        this.payload = Objects.requireNonNull(payload, "payload cannot be null");
        this.status = status != null ? status : OutboxStatus.PENDING;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.attemptCount = attemptCount;
        this.lastAttemptAt = lastAttemptAt;
        this.nextAttemptAt = nextAttemptAt != null ? nextAttemptAt : Instant.now();
        this.lastError = lastError;
        this.acknowledgedAt = acknowledgedAt;
        this.claimedBy = claimedBy;
        this.claimExpiresAt = claimExpiresAt;
        this.topic = topic != null ? topic : "parcel.lifecycle.v1";
        this.partitionKey = partitionKey != null ? partitionKey : aggregateId.toString();
    }

    public OutboxEvent(UUID eventId,
                       String aggregateType,
                       UUID aggregateId,
                       Long aggregateVersion,
                       String eventType,
                       String schemaVersion,
                       String payload,
                       String topic,
                       String partitionKey) {
        this(eventId, aggregateType, aggregateId, aggregateVersion, eventType, schemaVersion,
                payload, OutboxStatus.PENDING, Instant.now(), 0, null, Instant.now(), null, null, null, null,
                topic, partitionKey);
    }

    // Getters and Setters

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(UUID aggregateId) {
        this.aggregateId = aggregateId;
    }

    public Long getAggregateVersion() {
        return aggregateVersion;
    }

    public void setAggregateVersion(Long aggregateVersion) {
        this.aggregateVersion = aggregateVersion;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public void setStatus(OutboxStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public void setClaimExpiresAt(Instant claimExpiresAt) {
        this.claimExpiresAt = claimExpiresAt;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OutboxEvent that = (OutboxEvent) o;
        return Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }
}
