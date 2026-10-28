package com.swiftdrop.deliveryservice.tracking.service;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import com.swiftdrop.deliveryservice.tracking.dto.PublicTrackingResponse;
import com.swiftdrop.deliveryservice.tracking.dto.TrackingTimelineItem;
import com.swiftdrop.deliveryservice.tracking.exception.InvalidTrackingReferenceException;
import com.swiftdrop.deliveryservice.tracking.exception.TrackingNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Production implementation of PublicTrackingService.
 * Guarantees strictly read-only execution, zero mutation side effects, and strict allowlisting.
 */
@Service
@Transactional(readOnly = true)
public class DefaultPublicTrackingService implements PublicTrackingService {

    private static final Pattern TRACKING_PATTERN = Pattern.compile("^SWD-[0-9]{8}-[A-Z0-9]{4,8}$");
    private static final int MIN_REFERENCE_LENGTH = 17;
    private static final int MAX_REFERENCE_LENGTH = 32;

    private final ParcelRepository parcelRepository;
    private final StatusHistoryRepository statusHistoryRepository;

    public DefaultPublicTrackingService(ParcelRepository parcelRepository,
                                       StatusHistoryRepository statusHistoryRepository) {
        this.parcelRepository = Objects.requireNonNull(parcelRepository, "parcelRepository cannot be null");
        this.statusHistoryRepository = Objects.requireNonNull(statusHistoryRepository, "statusHistoryRepository cannot be null");
    }

    @Override
    public PublicTrackingResponse getPublicTracking(String trackingNumber) {
        validateTrackingNumber(trackingNumber);

        Parcel parcel = parcelRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new TrackingNotFoundException(trackingNumber));

        // Load append-only chronological history
        List<StatusHistory> historyRecords = statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(parcel.getParcelId());

        List<TrackingTimelineItem> timeline = new ArrayList<>();
        if (historyRecords.isEmpty()) {
            // Initial CREATED checkpoint if history table has no entries yet
            timeline.add(new TrackingTimelineItem(
                    parcel.getStatus(),
                    parcel.getCreatedAt(),
                    coarseZoneForStatus(parcel.getStatus())
            ));
        } else {
            for (StatusHistory record : historyRecords) {
                timeline.add(new TrackingTimelineItem(
                        record.getNewStatus(),
                        record.getOccurredAt(),
                        coarseZoneForStatus(record.getNewStatus())
                ));
            }
        }

        // Project strictly allowlisted public fields with coarse location precision (no PII, no exact addresses, no proof links)
        return new PublicTrackingResponse(
                parcel.getTrackingNumber(),
                parcel.getStatus(),
                "Colombo",
                "Gampaha",
                timeline
        );
    }

    private void validateTrackingNumber(String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new InvalidTrackingReferenceException("Tracking number cannot be empty.");
        }
        String trimmed = trackingNumber.trim();
        if (trimmed.length() < MIN_REFERENCE_LENGTH || trimmed.length() > MAX_REFERENCE_LENGTH) {
            throw new InvalidTrackingReferenceException("Tracking reference length must be between "
                    + MIN_REFERENCE_LENGTH + " and " + MAX_REFERENCE_LENGTH + " characters.");
        }
        if (!TRACKING_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidTrackingReferenceException("Tracking reference must match approved format SWD-YYYYMMDD-XXXX (e.g. SWD-20261023-A1B2C3).");
        }
    }

    private String coarseZoneForStatus(ParcelStatus status) {
        if (status == null) {
            return "Western Province Hub";
        }
        return switch (status) {
            case CREATED -> "Order Confirmed (Colombo)";
            case PICKED_UP -> "Picked Up (Western Province Sorting Hub)";
            case IN_TRANSIT -> "In Transit (Western Province Sorting Hub)";
            case OUT_FOR_DELIVERY -> "Out for Delivery (Local Zone)";
            case DELIVERED -> "Delivered";
            case FAILED -> "Delivery Attempted (Western Province)";
        };
    }
}
