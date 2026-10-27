package com.swiftdrop.deliveryservice.booking.controller;

import com.swiftdrop.deliveryservice.booking.BookingFailure;
import com.swiftdrop.deliveryservice.booking.BookingService;
import com.swiftdrop.deliveryservice.booking.MerchantBookParcelRequest;
import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
import com.swiftdrop.deliveryservice.security.KeycloakRoleExtractor;
import com.swiftdrop.deliveryservice.security.merchant.MerchantAuthenticationToken;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Controller exposing the approved merchant automated parcel booking route.
 * Matches contracts/rest/openapi-delivery.yaml: POST /api/v1/merchant/parcels.
 *
 * Enforces independent merchant authorization, tenant extraction, Idempotency-Key validation,
 * and single-consumption quote binding.
 */
@RestController
@RequestMapping("/api/v1/merchant/parcels")
public class MerchantParcelBookingController {

    private static final Logger log = LoggerFactory.getLogger(MerchantParcelBookingController.class);

    private final BookingService bookingService;

    public MerchantParcelBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<ParcelDetailResponse> createMerchantParcel(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody MerchantBookParcelRequest request) {

        // 1. Validate Idempotency-Key header format
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            throw new BookingFailure(400, "INVALID_REQUEST", "Idempotency-Key header is required.");
        }

        UUID idempotencyKey;
        try {
            idempotencyKey = UUID.fromString(idempotencyKeyHeader.trim());
        } catch (IllegalArgumentException ex) {
            throw new BookingFailure(400, "INVALID_REQUEST", "Idempotency-Key must be a valid UUID format.");
        }

        // 2. Establish authenticated merchant tenant identity (defense-in-depth)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new BookingFailure(401, "UNAUTHENTICATED", "Authentication required for merchant parcel booking.");
        }

        UUID merchantId;
        String callerSub;

        if (auth instanceof MerchantAuthenticationToken merchantAuth) {
            merchantId = merchantAuth.getMerchantId();
            callerSub = merchantAuth.getName();
        } else if (auth instanceof JwtAuthenticationToken jwtAuth) {
            var roles = KeycloakRoleExtractor.extractApprovedRoles(jwtAuth.getToken());
            if (!roles.contains("MERCHANT") && auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_MERCHANT"))) {
                throw new BookingFailure(403, "FORBIDDEN", "MERCHANT role required for merchant parcel booking.");
            }

            Object merchantClaim = jwtAuth.getToken().getClaim("merchant_id");
            if (merchantClaim == null || merchantClaim.toString().isBlank()) {
                throw new BookingFailure(403, "FORBIDDEN", "Verified token does not contain a merchant_id tenant claim.");
            }

            try {
                merchantId = UUID.fromString(merchantClaim.toString().trim());
            } catch (IllegalArgumentException ex) {
                throw new BookingFailure(403, "FORBIDDEN", "Malformed merchant_id tenant claim format.");
            }
            callerSub = jwtAuth.getToken().getSubject();
        } else {
            throw new BookingFailure(401, "UNAUTHENTICATED", "Unsupported or unverified authentication principal.");
        }

        log.debug("Initiating merchant parcel booking [merchantId={}, quoteId={}, key={}]",
                merchantId, request.quoteId(), idempotencyKey);

        ParcelDetailResponse response = bookingService.bookMerchant(merchantId, callerSub, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
