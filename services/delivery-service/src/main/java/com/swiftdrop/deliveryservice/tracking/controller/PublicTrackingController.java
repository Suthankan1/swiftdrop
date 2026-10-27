package com.swiftdrop.deliveryservice.tracking.controller;

import com.swiftdrop.deliveryservice.tracking.dto.PublicTrackingResponse;
import com.swiftdrop.deliveryservice.tracking.service.PublicTrackingService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for unauthenticated public parcel tracking.
 * Implements GET /api/v1/tracking/{trackingNumber} defined in openapi-delivery.yaml.
 *
 * Returns strictly allowlisted non-sensitive tracking milestones and enforces
 * no-store caching and search engine indexing restrictions.
 */
@RestController
@RequestMapping("/api/v1/tracking")
public class PublicTrackingController {

    private final PublicTrackingService trackingService;

    public PublicTrackingController(PublicTrackingService trackingService) {
        this.trackingService = trackingService;
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
}
