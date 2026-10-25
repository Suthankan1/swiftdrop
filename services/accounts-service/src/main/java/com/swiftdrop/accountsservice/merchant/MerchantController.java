package com.swiftdrop.accountsservice.merchant;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller exposing protected merchant tenant endpoints for authorized merchant principals and administrators.
 * Enforces tenant ownership checks, approval state resolution, and self-service merchant enrollment.
 */
@RestController
public class MerchantController {

    private final CurrentIdentityProvider identityProvider;
    private final MerchantTenantAuthorizationProvider tenantAuthorizationProvider;
    private final MerchantApprovalProvider approvalProvider;
    private final MerchantEnrollmentService enrollmentService;

    public MerchantController(CurrentIdentityProvider identityProvider,
                              MerchantTenantAuthorizationProvider tenantAuthorizationProvider,
                              MerchantApprovalProvider approvalProvider) {
        this(identityProvider, tenantAuthorizationProvider, approvalProvider, null);
    }

    @Autowired
    public MerchantController(CurrentIdentityProvider identityProvider,
                              MerchantTenantAuthorizationProvider tenantAuthorizationProvider,
                              MerchantApprovalProvider approvalProvider,
                              MerchantEnrollmentService enrollmentService) {
        this.identityProvider = Objects.requireNonNull(identityProvider, "CurrentIdentityProvider is required");
        this.tenantAuthorizationProvider = Objects.requireNonNull(tenantAuthorizationProvider, "MerchantTenantAuthorizationProvider is required");
        this.approvalProvider = Objects.requireNonNull(approvalProvider, "MerchantApprovalProvider is required");
        this.enrollmentService = enrollmentService;
    }

    /**
     * Submits a new merchant tenant enrollment for the authenticated caller.
     * Newly created merchants are assigned server-controlled PENDING approval status.
     */
    @PostMapping("/api/v1/merchants")
    public ResponseEntity<MerchantProfileResponse> registerMerchant(
            @Valid @RequestBody MerchantRegistrationRequest request) {
        var identity = identityProvider.currentIdentity();
        var response = enrollmentService.enrollMerchant(identity, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * Resolves the merchant tenant associated with the currently authenticated caller.
     */
    @GetMapping("/api/v1/merchants/me")
    public ResponseEntity<MerchantRecord> currentMerchant() {
        var identity = identityProvider.currentIdentity();
        var merchant = tenantAuthorizationProvider.resolveMerchantForIdentity(identity)
                .orElseThrow(() -> new MerchantNotFoundException("No merchant profile bound to current identity"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(merchant);
    }

    /**
     * Reads merchant tenant details by merchantId, verifying tenant ownership or ADMIN privilege.
     */
    @GetMapping("/api/v1/merchants/{merchantId}")
    public ResponseEntity<MerchantRecord> getMerchant(@PathVariable UUID merchantId) {
        var identity = identityProvider.currentIdentity();
        tenantAuthorizationProvider.requireTenantAccess(identity, merchantId);
        var merchant = tenantAuthorizationProvider.resolveMerchantById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + merchantId));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(merchant);
    }

    /**
     * Reads merchant approval status by merchantId, verifying tenant ownership or ADMIN privilege.
     */
    @GetMapping("/api/v1/merchants/{merchantId}/status")
    public ResponseEntity<MerchantStatusResponse> getMerchantStatus(@PathVariable UUID merchantId) {
        var identity = identityProvider.currentIdentity();
        tenantAuthorizationProvider.requireTenantAccess(identity, merchantId);
        var status = approvalProvider.getApprovalStatus(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + merchantId));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new MerchantStatusResponse(merchantId, status, status.isApproved()));
    }

    public record MerchantStatusResponse(UUID merchantId, MerchantStatus status, boolean approved) {}
}
