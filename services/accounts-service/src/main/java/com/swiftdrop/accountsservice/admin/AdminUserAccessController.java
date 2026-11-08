package com.swiftdrop.accountsservice.admin;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakIdentityAdapter;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;
import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import com.swiftdrop.accountsservice.reconciliation.ReconciliationAuditService;
import com.swiftdrop.accountsservice.staff.StaffAccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Lets an ADMIN disable or re-enable sign-in for an account (finding 62).
 * Disabling also ends the user's Keycloak sessions; every change is written to the reconciliation audit.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserAccessController {

    private final CurrentIdentityProvider identityProvider;
    private final AccountProfileRepository accounts;
    private final KeycloakIdentityAdapter identityAdapter;
    private final ReconciliationAuditService auditService;

    @Autowired
    public AdminUserAccessController(CurrentIdentityProvider identityProvider,
                                     AccountProfileRepository accounts,
                                     KeycloakIdentityAdapter identityAdapter,
                                     @Autowired(required = false) ReconciliationAuditService auditService) {
        this.identityProvider = identityProvider;
        this.accounts = accounts;
        this.identityAdapter = identityAdapter;
        this.auditService = auditService;
    }

    public record UserAccessResponse(UUID profileId, boolean enabled, Instant changedAt) { }

    @PostMapping("/{profileId}/disable")
    public ResponseEntity<UserAccessResponse> disable(@PathVariable UUID profileId) {
        return change(profileId, false);
    }

    @PostMapping("/{profileId}/enable")
    public ResponseEntity<UserAccessResponse> enable(@PathVariable UUID profileId) {
        return change(profileId, true);
    }

    private ResponseEntity<UserAccessResponse> change(UUID profileId, boolean enabled) {
        TrustedIdentity admin = identityProvider.currentIdentity();
        if (admin == null || admin.subject() == null || !admin.roles().contains("ADMIN")) {
            throw new StaffAccessDeniedException("Operation requires authenticated ADMIN role");
        }
        AccountProfile profile = accounts.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!enabled && admin.subject().equals(profile.getKeycloakSub())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Administrators cannot disable their own account");
        }
        identityAdapter.setUserEnabled(profile.getKeycloakSub(), enabled);
        if (auditService != null) {
            auditService.recordAudit(profile.getKeycloakSub(), "USER", enabled ? "ENABLE_USER" : "DISABLE_USER",
                    enabled ? "DISABLED" : "ENABLED", enabled ? "ENABLED" : "DISABLED",
                    ReconciliationOutcome.SUCCESS, admin.subject(), null, null);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new UserAccessResponse(profileId, enabled, Instant.now()));
    }
}
