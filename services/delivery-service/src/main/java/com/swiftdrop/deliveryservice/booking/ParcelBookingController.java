package com.swiftdrop.deliveryservice.booking;

import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
import com.swiftdrop.deliveryservice.security.KeycloakRoleExtractor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for booking new parcel shipments (POST /api/v1/parcels).
 * Conforms to contracts/rest/openapi-delivery.yaml.
 */
@RestController
@RequestMapping("/api/v1/parcels")
public class ParcelBookingController {

    private final ParcelBookingService bookingService;

    public ParcelBookingController(ParcelBookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ParcelDetailResponse> bookParcel(
            @Valid @RequestBody BookParcelRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {

        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new BookingFailure(401, "UNAUTHENTICATED", "Authentication required to book a parcel.");
        }

        List<String> roles = KeycloakRoleExtractor.extractApprovedRoles(jwt);
        if (!roles.contains("CUSTOMER") && !roles.contains("MERCHANT") && !roles.contains("ADMIN")) {
            throw new BookingFailure(403, "FORBIDDEN", "Only customers, merchants, or administrators may book parcels.");
        }

        UUID merchantId = null;
        if (roles.contains("MERCHANT")) {
            Object merchantClaim = jwt.getClaim("merchant_id");
            if (merchantClaim != null && !merchantClaim.toString().isBlank()) {
                try {
                    merchantId = UUID.fromString(merchantClaim.toString().trim());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ParcelDetailResponse response = bookingService.bookParcel(request, jwt.getSubject(), merchantId, idempotencyKey);
        URI location = URI.create("/api/v1/parcels/" + response.parcelId());
        return ResponseEntity.created(location).body(response);
    }

    @ExceptionHandler(BookingFailure.class)
    public ResponseEntity<ProblemDetail> handleBookingFailure(BookingFailure ex) {
        return ResponseEntity.status(ex.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ex.problem());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<BookingFailure.InvalidParameter> invalid = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new BookingFailure.InvalidParameter(f.getField(), f.getDefaultMessage()))
                .toList();
        BookingFailure failure = BookingFailure.validation(invalid);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(failure.problem());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMalformedJson(HttpMessageNotReadableException ex) {
        BookingFailure failure = new BookingFailure(400, "MALFORMED_JSON", "Malformed JSON request body.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(failure.problem());
    }
}

