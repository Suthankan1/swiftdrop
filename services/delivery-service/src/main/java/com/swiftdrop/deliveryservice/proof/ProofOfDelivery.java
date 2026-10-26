package com.swiftdrop.deliveryservice.proof;

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

    @Column(name = "object_reference", nullable = false, length = 128)
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

    public void updateMetadata(String recipientName, String deliveryNote) {
        this.recipientName = recipientName;
        this.deliveryNote = deliveryNote;
    }
}
