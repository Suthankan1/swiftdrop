package com.swiftdrop.deliveryservice.receipt.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptData;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultReceiptDataProviderTest {

    private static final UUID PARCEL_ID = UUID.randomUUID();
    private static final Instant ISSUED = Instant.parse("2026-11-07T10:00:00Z");
    private static final Instant DELIVERED = Instant.parse("2026-11-07T09:00:00Z");

    private final ParcelRepository parcels = mock(ParcelRepository.class);
    private final StatusHistoryRepository history = mock(StatusHistoryRepository.class);
    private final DefaultReceiptDataProvider provider =
            new DefaultReceiptDataProvider(parcels, history, Clock.fixed(ISSUED, ZoneOffset.UTC));

    private Parcel parcel(ParcelStatus status) {
        Parcel parcel = mock(Parcel.class);
        when(parcel.getParcelId()).thenReturn(PARCEL_ID);
        when(parcel.getTrackingNumber()).thenReturn("SWD-20261107-ABCD1234");
        when(parcel.getSenderSub()).thenReturn("customer-sub");
        when(parcel.getStatus()).thenReturn(status);
        when(parcel.getAmountMinorUnits()).thenReturn(59000L);
        when(parcel.getCurrency()).thenReturn("LKR");
        when(parcel.getRateVersion()).thenReturn("RATE-2026-V1");
        when(parcel.getPickupAddress()).thenReturn("10 Road");
        when(parcel.getDropoffAddress()).thenReturn("20 Lane");
        when(parcel.getRecipientName()).thenReturn("Uma");
        when(parcel.getRecipientPhone()).thenReturn("0771234567");
        when(parcels.findById(PARCEL_ID)).thenReturn(Optional.of(parcel));
        return parcel;
    }

    private void deliveredHistory() {
        StatusHistory delivered = mock(StatusHistory.class);
        when(delivered.getNewStatus()).thenReturn(ParcelStatus.DELIVERED);
        when(delivered.getOccurredAt()).thenReturn(DELIVERED);
        when(history.findByParcelIdOrderByOccurredAtDesc(PARCEL_ID)).thenReturn(List.of(delivered));
    }

    @Test
    void buildsReceiptFromDeliveredParcelAndItsHistory() {
        parcel(ParcelStatus.DELIVERED);
        deliveredHistory();

        ReceiptData receipt = provider.getAuthoritativeReceiptData(PARCEL_ID).orElseThrow();

        assertThat(receipt.amountMinorUnits()).isEqualTo(59000L);
        assertThat(receipt.deliveryCompletedAt()).isEqualTo(DELIVERED);
        assertThat(receipt.issuedAt()).isEqualTo(ISSUED);
        assertThat(receipt.pickupAddress()).isEqualTo("10 Road");
        assertThat(receipt.createdBySub()).isEqualTo("customer-sub");
    }

    @Test
    void marksMissingDetailsOnOlderParcelsInsteadOfFailing() {
        Parcel parcel = parcel(ParcelStatus.DELIVERED);
        when(parcel.getPickupAddress()).thenReturn(null);
        deliveredHistory();

        assertThat(provider.getAuthoritativeReceiptData(PARCEL_ID).orElseThrow().pickupAddress())
                .isEqualTo("Not recorded");
    }

    @Test
    void returnsNothingForParcelsNotDelivered() {
        parcel(ParcelStatus.FAILED);

        assertThat(provider.getAuthoritativeReceiptData(PARCEL_ID)).isEmpty();
    }

    @Test
    void returnsNothingForUnknownParcels() {
        when(parcels.findById(PARCEL_ID)).thenReturn(Optional.empty());

        assertThat(provider.getAuthoritativeReceiptData(PARCEL_ID)).isEmpty();
    }
}
