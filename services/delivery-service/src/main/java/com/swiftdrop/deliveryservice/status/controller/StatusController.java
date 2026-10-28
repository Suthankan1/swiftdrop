package com.swiftdrop.deliveryservice.status.controller;

import com.swiftdrop.deliveryservice.status.StatusTransitionService;
import com.swiftdrop.deliveryservice.status.dto.ParcelStatusChangeResponse;
import com.swiftdrop.deliveryservice.status.dto.UpdateStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

/**
 * REST Controller for guarded parcel status transitions.
 *
 * Implements REST contract endpoint PATCH /api/v1/parcels/{parcelId}/status (IF-REST-07).
 */
@RestController
@RequestMapping("/api/v1/parcels")
public class StatusController {

    private final StatusTransitionService statusTransitionService;

    public StatusController(StatusTransitionService statusTransitionService) {
        this.statusTransitionService = statusTransitionService;
    }

    @PatchMapping("/{parcelId}/status")
    public ResponseEntity<ParcelStatusChangeResponse> updateParcelStatus(
            @PathVariable("parcelId") UUID parcelId,
            @Valid @RequestBody UpdateStatusRequest request,
            Authentication authentication) {

        String actorSub = extractSubject(authentication);
        Collection<String> roles = extractRoles(authentication);

        ParcelStatusChangeResponse response = statusTransitionService.updateStatus(
                parcelId,
                request,
                actorSub,
                roles
        );

        return ResponseEntity.ok(response);
    }

    private String extractSubject(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return jwtToken.getToken().getSubject();
        }
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        if (authentication != null && authentication.getName() != null) {
            return authentication.getName();
        }
        return "anonymous";
    }

    private Collection<String> extractRoles(Authentication authentication) {
        if (authentication == null) {
            return Collections.emptyList();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }
}
