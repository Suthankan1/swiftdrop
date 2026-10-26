package com.swiftdrop.deliveryservice.assignment.controller;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentService;
import com.swiftdrop.deliveryservice.assignment.dto.AssignCourierRequest;
import com.swiftdrop.deliveryservice.assignment.dto.AssignmentResponse;
import com.swiftdrop.deliveryservice.query.dto.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Collections;

/**
 * REST Controller for courier assignment commands.
 *
 * Implements REST contract endpoint POST /api/v1/assignments in openapi-delivery.yaml.
 */
@RestController
@RequestMapping("/api/v1/assignments")
public class AssignmentController {

    private final DeliveryAssignmentService assignmentService;

    public AssignmentController(DeliveryAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @PostMapping
    public ResponseEntity<AssignmentResponse> assignCourier(
            @Valid @RequestBody AssignCourierRequest request,
            Authentication authentication) {

        String dispatcherSub = extractSubject(authentication);
        Collection<String> roles = extractRoles(authentication);

        AssignmentResponse response = assignmentService.assignCourier(
                request,
                dispatcherSub,
                roles
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    public ResponseEntity<PagedResponse<AssignmentResponse>> getMyAssignments(
            @RequestParam(name = "activeOnly", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication) {

        String courierSub = extractSubject(authentication);
        Collection<String> roles = extractRoles(authentication);

        PagedResponse<AssignmentResponse> response = assignmentService.getMyAssignments(
                courierSub,
                roles,
                activeOnly,
                PageRequest.of(page, size)
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<PagedResponse<AssignmentResponse>> listAssignments(
            @RequestParam(name = "activeOnly", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication) {
        return getMyAssignments(activeOnly, page, size, authentication);
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
