package com.swiftdrop.deliveryservice.query.controller;

import com.swiftdrop.deliveryservice.query.dto.PagedResponse;
import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
import com.swiftdrop.deliveryservice.query.dto.ParcelStatusHistoryResponse;
import com.swiftdrop.deliveryservice.query.service.DefaultParcelQueryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for authorized parcel detail, history, and aggregate read queries.
 * Conforms to OpenAPI 3.0.3 paths /api/v1/parcels/{parcelId} and /api/v1/parcels/{parcelId}/history.
 */
@RestController
@RequestMapping("/api/v1/parcels")
public class ParcelQueryController {

    private final DefaultParcelQueryService queryService;

    public ParcelQueryController(DefaultParcelQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Retrieves authoritative parcel details with object-level authorization (IF-REST-05).
     */
    @GetMapping("/{parcelId}")
    public ResponseEntity<ParcelDetailResponse> getParcelById(
            @PathVariable UUID parcelId,
            Authentication authentication) {

        Jwt jwt = extractJwt(authentication);
        ParcelDetailResponse response = queryService.getParcelDetail(parcelId, jwt);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves append-only status transition history for a parcel with object-level authorization.
     */
    @GetMapping("/{parcelId}/history")
    public ResponseEntity<PagedResponse<ParcelStatusHistoryResponse>> getParcelHistory(
            @PathVariable UUID parcelId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication) {

        Jwt jwt = extractJwt(authentication);
        PagedResponse<ParcelStatusHistoryResponse> response = queryService.getParcelHistory(
                parcelId,
                jwt,
                PageRequest.of(page, size)
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Lists parcels authorized for the calling actor (Customer or Merchant).
     */
    @GetMapping
    public ResponseEntity<PagedResponse<ParcelDetailResponse>> listParcels(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication) {

        Jwt jwt = extractJwt(authentication);
        PagedResponse<ParcelDetailResponse> response = queryService.listParcelsForCaller(
                jwt,
                PageRequest.of(page, size)
        );
        return ResponseEntity.ok(response);
    }

    private Jwt extractJwt(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return jwtToken.getToken();
        }
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw new IllegalStateException("Authentication token is missing or is not a valid JWT.");
    }
}
