package com.swiftdrop.accountsservice.staff;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Controller exposing authenticated staff self-read endpoints.
 * Resolves business profile strictly from the verified JWT principal.
 */
@RestController
public class StaffProfileController {

    private final CurrentIdentityProvider identityProvider;
    private final StaffProfileService staffProfileService;

    public StaffProfileController(CurrentIdentityProvider identityProvider,
                                  StaffProfileService staffProfileService) {
        this.identityProvider = Objects.requireNonNull(identityProvider, "CurrentIdentityProvider is required");
        this.staffProfileService = Objects.requireNonNull(staffProfileService, "StaffProfileService is required");
    }

    /**
     * Authenticated staff self-read endpoint.
     * Resolves the caller's staff profile bound to their Keycloak subject.
     * Accessible by COURIER, DISPATCHER, or ADMIN roles.
     */
    @GetMapping({"/api/v1/staff/me", "/api/v1/staff/profile"})
    public ResponseEntity<StaffProfileResponse> currentStaffProfile() {
        var identity = identityProvider.currentIdentity();
        var profile = staffProfileService.getStaffProfile(identity);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(profile);
    }
}
