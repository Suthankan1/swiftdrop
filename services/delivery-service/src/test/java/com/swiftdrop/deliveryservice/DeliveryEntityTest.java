package com.swiftdrop.deliveryservice;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.idempotency.IdempotencyRecord;
import com.swiftdrop.deliveryservice.idempotency.IdempotencyStatus;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryEntityTest {

    @Test
    @DisplayName("Verify Parcel entity properties, defaults, and identity")
    void testParcelEntity() {
        UUID parcelId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String trackingNumber = "SWD-20261025-ABCD";
        String senderSub = "keycloak-sub-123";

        Parcel parcel = new Parcel(
                parcelId,
                trackingNumber,
                senderSub,
                merchantId,
                quoteId,
                "v1.0",
                150000L,
                "LKR",
                new BigDecimal("2.500")
        );

        assertThat(parcel.getParcelId()).isEqualTo(parcelId);
        assertThat(parcel.getTrackingNumber()).isEqualTo(trackingNumber);
        assertThat(parcel.getSenderSub()).isEqualTo(senderSub);
        assertThat(parcel.getMerchantId()).isEqualTo(merchantId);
        assertThat(parcel.getQuoteId()).isEqualTo(quoteId);
        assertThat(parcel.getRateVersion()).isEqualTo("v1.0");
        assertThat(parcel.getAmountMinorUnits()).isEqualTo(150000L);
        assertThat(parcel.getCurrency()).isEqualTo("LKR");
        assertThat(parcel.getWeightKg()).isEqualByComparingTo("2.500");
        assertThat(parcel.getStatus()).isEqualTo(ParcelStatus.CREATED);
        assertThat(parcel.getVersion()).isEqualTo(1L);

        // Mutate status and version
        parcel.setStatus(ParcelStatus.PICKED_UP);
        parcel.setVersion(2L);
        assertThat(parcel.getStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(parcel.getVersion()).isEqualTo(2L);

        // Equals and hashCode by ID
        Parcel other = new Parcel();
        other.setParcelId(parcelId);
        assertThat(parcel).isEqualTo(other);
        assertThat(parcel.hashCode()).isEqualTo(other.hashCode());
    }

    @Test
    @DisplayName("Verify StatusHistory entity properties and immutability attributes")
    void testStatusHistoryEntity() {
        UUID historyId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID proofId = UUID.randomUUID();
        Instant now = Instant.now();

        StatusHistory history = new StatusHistory(
                historyId,
                parcelId,
                ParcelStatus.OUT_FOR_DELIVERY,
                ParcelStatus.DELIVERED,
                null,
                "Left with security desk",
                proofId,
                "courier-sub-999",
                5L,
                now
        );

        assertThat(history.getHistoryId()).isEqualTo(historyId);
        assertThat(history.getParcelId()).isEqualTo(parcelId);
        assertThat(history.getPreviousStatus()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
        assertThat(history.getNewStatus()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(history.getReason()).isNull();
        assertThat(history.getNote()).isEqualTo("Left with security desk");
        assertThat(history.getProofId()).isEqualTo(proofId);
        assertThat(history.getActorSub()).isEqualTo("courier-sub-999");
        assertThat(history.getResultingVersion()).isEqualTo(5L);
        assertThat(history.getOccurredAt()).isEqualTo(now);

        StatusHistory other = new StatusHistory();
        assertThat(history).isNotEqualTo(other);
    }

    @Test
    @DisplayName("Verify IdempotencyRecord entity properties and status lifecycle")
    void testIdempotencyRecordEntity() {
        UUID recordId = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(86400);

        IdempotencyRecord record = new IdempotencyRecord(
                recordId,
                "tenant-123",
                "idem-key-abc",
                "sha256-digest-xyz",
                IdempotencyStatus.IN_PROGRESS,
                null,
                expires
        );

        assertThat(record.getRecordId()).isEqualTo(recordId);
        assertThat(record.getPrincipalOrTenant()).isEqualTo("tenant-123");
        assertThat(record.getIdempotencyKey()).isEqualTo("idem-key-abc");
        assertThat(record.getRequestDigest()).isEqualTo("sha256-digest-xyz");
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.getStoredResult()).isNull();
        assertThat(record.getExpiresAt()).isEqualTo(expires);

        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setStoredResult("{\"trackingNumber\": \"SWD-123\"}");
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getStoredResult()).contains("SWD-123");
    }
}
