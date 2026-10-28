package com.swiftdrop.communicationsservice.consumer.quarantine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable audit and quarantine entity for invalid, malformed, or poison Kafka events.
 * Ensures zero silent loss while preventing partition-blocking poison message loops.
 *
 * Invariant:
 * Zero raw payload bodies or customer PII are persisted. Only cryptographic digests
 * and sanitized envelope metadata are retained.
 */
@Entity
@Table(name = "poison_event_quarantine")
public class PoisonEventQuarantine {

    @Id
    @Column(name = "quarantine_id", nullable = false)
    private UUID quarantineId;

    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;

    @Column(name = "source_topic", nullable = false, length = 100)
    private String sourceTopic;

    @Column(name = "source_partition", nullable = false)
    private Integer sourcePartition;

    @Column(name = "source_offset", nullable = false)
    private Long sourceOffset;

    @Column(name = "event_id_if_valid")
    private UUID eventIdIfValid;

    @Column(name = "event_type_if_valid", length = 100)
    private String eventTypeIfValid;

    @Column(name = "schema_version_if_valid", length = 50)
    private String schemaVersionIfValid;

    @Column(name = "failure_category", nullable = false, length = 50)
    private String failureCategory;

    @Column(name = "safe_error_summary", nullable = false, length = 500)
    private String safeErrorSummary;

    @Column(name = "payload_digest", nullable = false, length = 64)
    private String payloadDigest;

    @Column(name = "correlation_reference_if_safe", length = 64)
    private String correlationReferenceIfSafe;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "QUARANTINED";

    @Column(name = "quarantined_at", nullable = false)
    private Instant quarantinedAt;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    @Column(name = "replayed_by_actor_sub", length = 255)
    private String replayedByActorSub;

    @Column(name = "replay_reason", length = 500)
    private String replayReason;

    public PoisonEventQuarantine() {
    }

    public PoisonEventQuarantine(UUID quarantineId, String consumerGroup, String sourceTopic,
                                 Integer sourcePartition, Long sourceOffset, UUID eventIdIfValid,
                                 String eventTypeIfValid, String schemaVersionIfValid,
                                 String failureCategory, String safeErrorSummary,
                                 String payloadDigest, String correlationReferenceIfSafe,
                                 String status, Instant quarantinedAt) {
        this.quarantineId = quarantineId != null ? quarantineId : UUID.randomUUID();
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup cannot be null");
        this.sourceTopic = Objects.requireNonNull(sourceTopic, "sourceTopic cannot be null");
        this.sourcePartition = Objects.requireNonNull(sourcePartition, "sourcePartition cannot be null");
        this.sourceOffset = Objects.requireNonNull(sourceOffset, "sourceOffset cannot be null");
        this.eventIdIfValid = eventIdIfValid;
        this.eventTypeIfValid = eventTypeIfValid;
        this.schemaVersionIfValid = schemaVersionIfValid;
        this.failureCategory = Objects.requireNonNull(failureCategory, "failureCategory cannot be null");
        this.safeErrorSummary = Objects.requireNonNull(safeErrorSummary, "safeErrorSummary cannot be null");
        this.payloadDigest = Objects.requireNonNull(payloadDigest, "payloadDigest cannot be null");
        this.correlationReferenceIfSafe = correlationReferenceIfSafe;
        this.status = status != null ? status : "QUARANTINED";
        this.quarantinedAt = quarantinedAt != null ? quarantinedAt : Instant.now();
    }

    @PrePersist
    public void prePersist() {
        if (this.quarantineId == null) {
            this.quarantineId = UUID.randomUUID();
        }
        if (this.quarantinedAt == null) {
            this.quarantinedAt = Instant.now();
        }
        if (this.status == null) {
            this.status = "QUARANTINED";
        }
    }

    public UUID getQuarantineId() {
        return quarantineId;
    }

    public void setQuarantineId(UUID quarantineId) {
        this.quarantineId = quarantineId;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getSourceTopic() {
        return sourceTopic;
    }

    public void setSourceTopic(String sourceTopic) {
        this.sourceTopic = sourceTopic;
    }

    public Integer getSourcePartition() {
        return sourcePartition;
    }

    public void setSourcePartition(Integer sourcePartition) {
        this.sourcePartition = sourcePartition;
    }

    public Long getSourceOffset() {
        return sourceOffset;
    }

    public void setSourceOffset(Long sourceOffset) {
        this.sourceOffset = sourceOffset;
    }

    public UUID getEventIdIfValid() {
        return eventIdIfValid;
    }

    public void setEventIdIfValid(UUID eventIdIfValid) {
        this.eventIdIfValid = eventIdIfValid;
    }

    public String getEventTypeIfValid() {
        return eventTypeIfValid;
    }

    public void setEventTypeIfValid(String eventTypeIfValid) {
        this.eventTypeIfValid = eventTypeIfValid;
    }

    public String getSchemaVersionIfValid() {
        return schemaVersionIfValid;
    }

    public void setSchemaVersionIfValid(String schemaVersionIfValid) {
        this.schemaVersionIfValid = schemaVersionIfValid;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
    }

    public String getSafeErrorSummary() {
        return safeErrorSummary;
    }

    public void setSafeErrorSummary(String safeErrorSummary) {
        this.safeErrorSummary = safeErrorSummary;
    }

    public String getPayloadDigest() {
        return payloadDigest;
    }

    public void setPayloadDigest(String payloadDigest) {
        this.payloadDigest = payloadDigest;
    }

    public String getCorrelationReferenceIfSafe() {
        return correlationReferenceIfSafe;
    }

    public void setCorrelationReferenceIfSafe(String correlationReferenceIfSafe) {
        this.correlationReferenceIfSafe = correlationReferenceIfSafe;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getQuarantinedAt() {
        return quarantinedAt;
    }

    public void setQuarantinedAt(Instant quarantinedAt) {
        this.quarantinedAt = quarantinedAt;
    }

    public Instant getReplayedAt() {
        return replayedAt;
    }

    public void setReplayedAt(Instant replayedAt) {
        this.replayedAt = replayedAt;
    }

    public String getReplayedByActorSub() {
        return replayedByActorSub;
    }

    public void setReplayedByActorSub(String replayedByActorSub) {
        this.replayedByActorSub = replayedByActorSub;
    }

    public String getReplayReason() {
        return replayReason;
    }

    public void setReplayReason(String replayReason) {
        this.replayReason = replayReason;
    }
}
