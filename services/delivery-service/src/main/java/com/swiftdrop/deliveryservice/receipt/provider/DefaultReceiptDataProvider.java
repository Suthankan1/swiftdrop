package com.swiftdrop.deliveryservice.receipt.provider;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptData;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptLineItem;
import com.swiftdrop.deliveryservice.receipt.port.ReceiptDataProvider;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds receipts for delivered parcels from the parcel's booked fee snapshot and status history.
 * SwiftDrop has no payment provider, so the booked fee is treated as the settled amount.
 */
@Component
@ConditionalOnMissingBean(name = "testReceiptDataProvider")
public class DefaultReceiptDataProvider implements ReceiptDataProvider {

    private static final String NOT_RECORDED = "Not recorded";

    private final ParcelRepository parcelRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultReceiptDataProvider(ParcelRepository parcelRepository,
                                      StatusHistoryRepository statusHistoryRepository) {
        this(parcelRepository, statusHistoryRepository, Clock.systemUTC());
    }

    DefaultReceiptDataProvider(ParcelRepository parcelRepository,
                               StatusHistoryRepository statusHistoryRepository,
                               Clock clock) {
        this.parcelRepository = parcelRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.clock = clock;
    }

    @Override
    public Optional<ReceiptData> getAuthoritativeReceiptData(UUID parcelId) {
        return parcelRepository.findById(parcelId)
                .filter(parcel -> parcel.getStatus() == ParcelStatus.DELIVERED)
                .filter(parcel -> parcel.getAmountMinorUnits() != null)
                .flatMap(this::toReceipt);
    }

    private Optional<ReceiptData> toReceipt(Parcel parcel) {
        Optional<Instant> deliveredAt = statusHistoryRepository
                .findByParcelIdOrderByOccurredAtDesc(parcel.getParcelId()).stream()
                .filter(entry -> entry.getNewStatus() == ParcelStatus.DELIVERED)
                .map(entry -> entry.getOccurredAt())
                .findFirst();
        if (deliveredAt.isEmpty()) {
            return Optional.empty();
        }
        String tracking = parcel.getTrackingNumber();
        return Optional.of(new ReceiptData(
                "RCP-" + tracking,
                parcel.getParcelId(),
                tracking,
                parcel.getSenderSub(),
                parcel.getMerchantId(),
                orNotRecorded(parcel.getRecipientName()),
                orNotRecorded(parcel.getRecipientPhone()),
                orNotRecorded(parcel.getPickupAddress()),
                orNotRecorded(parcel.getDropoffAddress()),
                ReceiptData.ELIGIBLE_STATUS,
                deliveredAt.get(),
                clock.instant(),
                orNotRecorded(parcel.getRateVersion()),
                "BOOKING-" + tracking,
                true,
                parcel.getCurrency(),
                parcel.getAmountMinorUnits(),
                List.of(new ReceiptLineItem("Delivery fee", parcel.getAmountMinorUnits(), parcel.getCurrency()))));
    }

    private static String orNotRecorded(String value) {
        return value == null || value.isBlank() ? NOT_RECORDED : value;
    }
}
