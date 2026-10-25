package com.swiftdrop.deliveryservice.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical Delivery Aggregate Root Entity.
 *
 * Owned exclusively by the Delivery Operations bounded context (delivery_db.parcels).
 * External references (senderSub, merchantId, quoteId) are stored as opaque scalars.
 */
@Entity
@Table(name = "parcels")
public class Parcel {

    @Id
    @Column(name = "parcel_id", nullable = false, updatable = false)
    private UUID parcelId;

    @Column(name = "tracking_number", nullable = false, unique = true, length = 32)
    private String trackingNumber;

    @Column(name = "sender_sub", nullable = false, length = 64)
    private String senderSub;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "quote_id")
    private UUID quoteId;

    @Column(name = "rate_version", length = 32)
    private String rateVersion;

    @Column(name = "amount_minor_units")
    private Long amountMinorUnits;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "LKR";

    @Column(name = "weight_kg", precision = 8, scale = 3)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ParcelStatus status = ParcelStatus.CREATED;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 1L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Parcel() {
    }

    public Parcel(UUID parcelId,
                  String trackingNumber,
                  String senderSub,
                  UUID merchantId,
                  UUID quoteId,
                  String rateVersion,
                  Long amountMinorUnits,
                  String currency,
                  BigDecimal weightKg) {
        this.parcelId = Objects.requireNonNull(parcelId, "parcelId cannot be null");
        this.trackingNumber = Objects.requireNonNull(trackingNumber, "trackingNumber cannot be null");
        this.senderSub = Objects.requireNonNull(senderSub, "senderSub cannot be null");
        this.merchantId = merchantId;
        this.quoteId = quoteId;
        this.rateVersion = rateVersion;
        this.amountMinorUnits = amountMinorUnits;
        this.currency = currency != null ? currency : "LKR";
        this.weightKg = weightKg;
        this.status = ParcelStatus.CREATED;
        this.version = 1L;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.status == null) {
            this.status = ParcelStatus.CREATED;
        }
        if (this.version == null) {
            this.version = 1L;
        }
        if (this.currency == null) {
            this.currency = "LKR";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters

    public UUID getParcelId() {
        return parcelId;
    }

    public void setParcelId(UUID parcelId) {
        this.parcelId = parcelId;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public String getSenderSub() {
        return senderSub;
    }

    public void setSenderSub(String senderSub) {
        this.senderSub = senderSub;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public UUID getQuoteId() {
        return quoteId;
    }

    public void setQuoteId(UUID quoteId) {
        this.quoteId = quoteId;
    }

    public String getRateVersion() {
        return rateVersion;
    }

    public void setRateVersion(String rateVersion) {
        this.rateVersion = rateVersion;
    }

    public Long getAmountMinorUnits() {
        return amountMinorUnits;
    }

    public void setAmountMinorUnits(Long amountMinorUnits) {
        this.amountMinorUnits = amountMinorUnits;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(BigDecimal weightKg) {
        this.weightKg = weightKg;
    }

    public ParcelStatus getStatus() {
        return status;
    }

    public void setStatus(ParcelStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Parcel parcel = (Parcel) o;
        return Objects.equals(parcelId, parcel.parcelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parcelId);
    }
}
