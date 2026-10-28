package com.swiftdrop.deliveryservice.tracking.controller;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.sse.service.ParcelSseConnectionManager;
import com.swiftdrop.deliveryservice.tracking.dto.PublicTrackingResponse;
import com.swiftdrop.deliveryservice.tracking.exception.InvalidTrackingReferenceException;
import com.swiftdrop.deliveryservice.tracking.exception.TrackingNotFoundException;
import com.swiftdrop.deliveryservice.tracking.service.PublicTrackingService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * REST controller for unauthenticated public parcel tracking and live SSE streaming.
 * Implements GET /api/v1/tracking/{trackingNumber} and GET /api/v1/tracking/{trackingNumber}/events
 * defined in contracts/rest/openapi-delivery.yaml.
 *
 * Returns strictly allowlisted non-sensitive tracking milestones and enforces
 * no-store caching, search engine indexing restrictions, and anonymous SSE bounds.
 */
@RestController
@RequestMapping("/api/v1/tracking")
public class PublicTrackingController {

    private static final Pattern TRACKING_PATTERN = Pattern.compile("^SWD-[0-9]{8}-[A-Z0-9]{4,8}$");
    private static final int MIN_REFERENCE_LENGTH = 17;
    private static final int MAX_REFERENCE_LENGTH = 32;

    private final PublicTrackingService trackingService;
    private final ParcelRepository parcelRepository;
    private final ParcelSseConnectionManager connectionManager;

    public PublicTrackingController(
            PublicTrackingService trackingService,
            ParcelRepository parcelRepository,
            ParcelSseConnectionManager connectionManager) {
        this.trackingService = Objects.requireNonNull(trackingService, "trackingService cannot be null");
        this.parcelRepository = Objects.requireNonNull(parcelRepository, "parcelRepository cannot be null");
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
    }

    @GetMapping("/{trackingNumber}")
    public ResponseEntity<PublicTrackingResponse> getPublicTracking(@PathVariable String trackingNumber) {
        PublicTrackingResponse response = trackingService.getPublicTracking(trackingNumber);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(response);
    }

    @GetMapping(path = "/{trackingNumber}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamPublicTrackingEvents(@PathVariable String trackingNumber) {
        validateTrackingNumber(trackingNumber);

        Parcel parcel = parcelRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new TrackingNotFoundException(trackingNumber));

        SseEmitter emitter = connectionManager.registerPublicSession(
                parcel.getParcelId(),
                parcel.getTrackingNumber()
        );

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(emitter);
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
}
