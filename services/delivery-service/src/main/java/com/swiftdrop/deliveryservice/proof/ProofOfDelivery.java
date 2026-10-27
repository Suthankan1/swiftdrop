package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.proof.retention.RetentionStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Proof of Delivery aggregate entity.
 * Persists verified delivery evidence metadata and opaque private object storage reference.
 * Zero image binaries or credentials are stored here.
 */
@Entity
@Table(name = "proof_of_deliveries")
public class ProofOfDelivery {

    @Id
    @Column(name = "proof_id", nullable = false)
    private UUID proofId;

    @Column(name = "parcel_id", nullable = false, unique = true)
    private UUID parcelId;

    @Column(name = "object_reference", length = 128)
    private String objectReference;

    @Column(name = "evidence_type", nullable = false, length = 32)
    private String evidenceType;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "content_length", nullable = false)
    private Long contentLength;

    @Column(name = "recipient_name", length = 128)
    private String recipientName;

    @Column(name = "delivery_note", length = 512)
    private String deliveryNote;

    @Column(name = "uploaded_by_sub", nullable = false, length = 64)
    private String uploadedBySub;

    @Enumerated(EnumType.STRING)
    @Column(name = "retention_status", nullable = false, length = 32)
    private RetentionStatus retentionStatus = RetentionStatus.ACTIVE;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ProofOfDelivery() {
    }

    public ProofOfDelivery(UUID proofId,
                           UUID parcelId,
                           String objectReference,
                           String evidenceType,
                           String contentType,
                           Long contentLength,
                           String recipientName,
                           String deliveryNote,
                           String uploadedBySub,
                           Instant createdAt) {
        this.proofId = Objects.requireNonNull(proofId, "proofId cannot be null");
        this.parcelId = Objects.requireNonNull(parcelId, "parcelId cannot be null");
        this.objectReference = Objects.requireNonNull(objectReference, "objectReference cannot be null");
        this.evidenceType = evidenceType != null ? evidenceType : "PHOTO";
        this.contentType = contentType != null ? contentType : "image/jpeg";
        this.contentLength = Objects.requireNonNull(contentLength, "contentLength cannot be null");
        this.recipientName = recipientName;
        this.deliveryNote = deliveryNote;
        this.uploadedBySub = Objects.requireNonNull(uploadedBySub, "uploadedBySub cannot be null");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.retentionStatus = RetentionStatus.ACTIVE;
        this.purgedAt = null;
    }

    public UUID getProofId() {
        return proofId;
    }

    public UUID getParcelId() {
        return parcelId;
    }

    public String getObjectReference() {
        return objectReference;
    }

    public String getEvidenceType() {
        return evidenceType;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getContentLength() {
        return contentLength;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getDeliveryNote() {
        return deliveryNote;
    }

    public String getUploadedBySub() {
        return uploadedBySub;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }

    public RetentionStatus getRetentionStatus() {
        return retentionStatus;
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }

    public boolean isPurged() {
        return retentionStatus == RetentionStatus.PURGED;
    }

    public void updateMetadata(String recipientName, String deliveryNote) {
        this.recipientName = recipientName;
        this.deliveryNote = deliveryNote;
    }

    /**
     * Safely updates active evidence details during an authorized proof replacement.
     */
    public void replaceEvidence(String newObjectReference,
                                String newContentType,
                                Long newContentLength,
                                String newRecipientName,
                                String newDeliveryNote) {
        this.objectReference = Objects.requireNonNull(newObjectReference, "newObjectReference cannot be null");
        this.contentType = newContentType != null ? newContentType : "image/jpeg";
        this.contentLength = Objects.requireNonNull(newContentLength, "newContentLength cannot be null");
        this.recipientName = newRecipientName;
        this.deliveryNote = newDeliveryNote;
    }

    /**
     * Atomically transitions this record into a privacy-safe tombstone outcome.
     * Clears all private storage references, contact details, and notes while preserving
     * minimal non-sensitive audit metadata (proofId, parcelId, created_at, purged_at).
     */
    public void markPurged(Instant purgeInstant) {
        this.retentionStatus = RetentionStatus.PURGED;
        this.purgedAt = purgeInstant != null ? purgeInstant : Instant.now();
        this.objectReference = null;
        this.recipientName = null;
        this.deliveryNote = null;
    }
}
