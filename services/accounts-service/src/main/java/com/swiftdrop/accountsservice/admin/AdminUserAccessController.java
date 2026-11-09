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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** Fields left null keep their current value. */
    public record UpdateUserRequest(String fullName, String phoneNumber) { }

    public record UpdatedUserResponse(UUID profileId, String email, String fullName, String phoneNumber) { }

    private static final java.util.regex.Pattern PHONE = java.util.regex.Pattern.compile("^\\+?[0-9\\s\\-()]{7,25}$");

    /** Admin correction of a user's name and phone number (finding 62). Email is owned by the identity provider. */
    @PatchMapping("/{profileId}")
    @Transactional
    public ResponseEntity<UpdatedUserResponse> update(@PathVariable UUID profileId,
                                                      @RequestBody(required = false) UpdateUserRequest request) {
        TrustedIdentity admin = requireAdmin();
        if (request == null || (request.fullName() == null && request.phoneNumber() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide fullName and/or phoneNumber");
        }
        AccountProfile profile = accounts.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String fullName = request.fullName() == null ? profile.getFullName() : request.fullName().trim();
        String phone = request.phoneNumber() == null ? profile.getPhoneNumber() : request.phoneNumber().trim();
        if (fullName.isEmpty() || fullName.length() > 255 || fullName.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Full name must be 1 to 255 characters without control characters");
        }
        if (!phone.isEmpty() && !PHONE.matcher(phone).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone number must be 7 to 25 digits, spaces, dashes or brackets, optionally starting with +");
        }
        accounts.updateContactDetails(profileId, fullName, phone);
        if (auditService != null) {
            auditService.recordAudit(profile.getKeycloakSub(), "USER", "UPDATE_USER", null, null,
                    ReconciliationOutcome.SUCCESS, admin.subject(), null, null);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new UpdatedUserResponse(profileId, profile.getEmail(), fullName, phone));
    }

    private TrustedIdentity requireAdmin() {
        TrustedIdentity admin = identityProvider.currentIdentity();
        if (admin == null || admin.subject() == null || !admin.roles().contains("ADMIN")) {
            throw new StaffAccessDeniedException("Operation requires authenticated ADMIN role");
        }
        return admin;
    }

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
