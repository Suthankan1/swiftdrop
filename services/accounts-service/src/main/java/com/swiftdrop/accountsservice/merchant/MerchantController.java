package com.swiftdrop.accountsservice.merchant;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import com.swiftdrop.accountsservice.identity.TrustedIdentity;
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
    private final com.swiftdrop.accountsservice.reconciliation.MerchantRoleReconciliationService merchantReconciliationService;
    private final MerchantRepository merchantRepository;

    public MerchantController(CurrentIdentityProvider identityProvider,
                              MerchantTenantAuthorizationProvider tenantAuthorizationProvider,
                              MerchantApprovalProvider approvalProvider) {
        this(identityProvider, tenantAuthorizationProvider, approvalProvider, null, null, null);
    }

    public MerchantController(CurrentIdentityProvider identityProvider,
                              MerchantTenantAuthorizationProvider tenantAuthorizationProvider,
                              MerchantApprovalProvider approvalProvider,
                              MerchantEnrollmentService enrollmentService) {
        this(identityProvider, tenantAuthorizationProvider, approvalProvider, enrollmentService, null, null);
    }

    @Autowired
    public MerchantController(CurrentIdentityProvider identityProvider,
                              MerchantTenantAuthorizationProvider tenantAuthorizationProvider,
                              MerchantApprovalProvider approvalProvider,
                              MerchantEnrollmentService enrollmentService,
                              @Autowired(required = false) com.swiftdrop.accountsservice.reconciliation.MerchantRoleReconciliationService merchantReconciliationService,
                              @Autowired(required = false) MerchantRepository merchantRepository) {
        this.identityProvider = Objects.requireNonNull(identityProvider, "CurrentIdentityProvider is required");
        this.tenantAuthorizationProvider = Objects.requireNonNull(tenantAuthorizationProvider, "MerchantTenantAuthorizationProvider is required");
        this.approvalProvider = Objects.requireNonNull(approvalProvider, "MerchantApprovalProvider is required");
        this.enrollmentService = enrollmentService;
        this.merchantReconciliationService = merchantReconciliationService;
        this.merchantRepository = merchantRepository;
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

    /**
     * Approves a pending merchant enrollment. Requires authenticated ADMIN role.
     * Conforms to OpenAPI /api/v1/merchants/{id}/approve contract.
     */
    @PostMapping("/api/v1/merchants/{id}/approve")
    public ResponseEntity<MerchantProfileResponse> approveMerchant(
            @PathVariable UUID id,
            @RequestBody(required = false) AdminMerchantDecisionRequest request) {
        var identity = identityProvider.currentIdentity();
        validateAdminCaller(identity);
        if (merchantReconciliationService == null || merchantRepository == null) {
            throw new IllegalStateException("Merchant reconciliation service is not configured");
        }
        Long expectedVersion = request != null ? request.resolveExpectedVersion() : null;
        String reviewToken = request != null ? request.reviewToken() : null;
        merchantReconciliationService.approveMerchant(identity, id, expectedVersion, reviewToken);
        var updated = merchantRepository.findById(id)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + id));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MerchantProfileResponse.fromEntity(updated));
    }

    /**
     * Suspends an approved merchant tenant. Requires authenticated ADMIN role and mandatory non-empty reason.
     */
    @PostMapping("/api/v1/merchants/{id}/suspend")
    public ResponseEntity<MerchantProfileResponse> suspendMerchant(
            @PathVariable UUID id,
            @RequestBody(required = false) AdminMerchantDecisionRequest request) {
        var identity = identityProvider.currentIdentity();
        validateAdminCaller(identity);
        if (merchantReconciliationService == null || merchantRepository == null) {
            throw new IllegalStateException("Merchant reconciliation service is not configured");
        }
        if (request == null || request.reason() == null || request.reason().trim().isEmpty()) {
            throw new MerchantDecisionValidationException("Suspension requires a non-empty reason.");
        }
        Long expectedVersion = request.resolveExpectedVersion();
        merchantReconciliationService.suspendMerchant(identity, id, request.reason(), expectedVersion, request.reviewToken());
        var updated = merchantRepository.findById(id)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + id));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MerchantProfileResponse.fromEntity(updated));
    }

    private void validateAdminCaller(TrustedIdentity identity) {
        if (identity == null || identity.subject() == null || identity.subject().isBlank()) {
            throw new com.swiftdrop.accountsservice.staff.StaffAccessDeniedException("Authenticated identity is required");
        }
        if (!identity.roles().contains("ADMIN")) {
            throw new com.swiftdrop.accountsservice.staff.StaffAccessDeniedException("Operation requires authenticated ADMIN role. Caller roles: " + identity.roles());
        }
    }

    public record MerchantStatusResponse(UUID merchantId, MerchantStatus status, boolean approved) {}
}
