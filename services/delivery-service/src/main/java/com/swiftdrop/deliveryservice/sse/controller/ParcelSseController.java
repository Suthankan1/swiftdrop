package com.swiftdrop.deliveryservice.sse.controller;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.query.exception.ParcelAccessDeniedException;
import com.swiftdrop.deliveryservice.query.service.ParcelAuthorizationService;
import com.swiftdrop.deliveryservice.security.KeycloakRoleExtractor;
import com.swiftdrop.deliveryservice.sse.service.ParcelSseConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller exposing authenticated Server-Sent Events (SSE) lifecycle stream
 * for an authorized parcel viewer.
 *
 * Adheres strictly to contracts/rest/openapi-delivery.yaml:
 * GET /api/v1/parcels/{parcelId}/events (text/event-stream)
 */
@RestController
@RequestMapping("/api/v1/parcels")
public class ParcelSseController {

    private static final Logger log = LoggerFactory.getLogger(ParcelSseController.class);

    private final ParcelRepository parcelRepository;
    private final ParcelAuthorizationService authorizationService;
    private final ParcelSseConnectionManager connectionManager;

    public ParcelSseController(
            ParcelRepository parcelRepository,
            ParcelAuthorizationService authorizationService,
            ParcelSseConnectionManager connectionManager) {
        this.parcelRepository = Objects.requireNonNull(parcelRepository, "parcelRepository cannot be null");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService cannot be null");
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
    }

    @GetMapping(path = "/{parcelId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamParcelEvents(
            @PathVariable UUID parcelId,
            @AuthenticationPrincipal Jwt jwt) {

        // 1. Enforce verified Keycloak JWT
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ParcelAccessDeniedException(parcelId, "Authenticated subject is required to connect to parcel event stream.");
        }

        // 2. Token expiry check
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new ParcelAccessDeniedException(parcelId, "JWT token is expired or has invalid expiration timestamp.");
        }

        // 3. Look up authoritative parcel record
        Parcel parcel = parcelRepository.findById(parcelId)
                .orElseThrow(() -> new ParcelNotFoundException(parcelId));

        // 4. Enforce object-level authorization (Sender / Merchant / Courier / Admin / Dispatcher)
        if (!authorizationService.isAuthorizedToRead(jwt, parcel)) {
            log.warn("Access denied: subject {} is not authorized to stream events for parcel {}",
                    jwt.getSubject(), parcelId);
            throw new ParcelAccessDeniedException(parcelId);
        }

        // 5. Determine if caller is an assigned courier (for subsequent re-check before broadcast)
        List<String> roles = KeycloakRoleExtractor.extractApprovedRoles(jwt);
        boolean isCourier = roles.contains("COURIER") && !roles.contains("ADMIN") && !roles.contains("DISPATCHER");

        // 6. Register session and return SseEmitter
        return connectionManager.registerSession(
                parcelId,
                jwt.getSubject(),
                isCourier,
                expiresAt
        );
    }
}
