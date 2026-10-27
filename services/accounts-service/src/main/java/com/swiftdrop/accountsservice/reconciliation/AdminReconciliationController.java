package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationResult;
import com.swiftdrop.accountsservice.staff.StaffProfileResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller exposing controlled, administrative identity reconciliation endpoints.
 * Strictly guarded by Spring Security requiring the ADMIN role.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
public class AdminReconciliationController {

    private final CurrentIdentityProvider identityProvider;
    private final StaffRoleReconciliationService staffReconciliationService;
    private final MerchantRoleReconciliationService merchantReconciliationService;
    private final ReconciliationRecoveryService recoveryService;
    private final ReconciliationAuditService auditService;

    public AdminReconciliationController(CurrentIdentityProvider identityProvider,
                                         StaffRoleReconciliationService staffReconciliationService,
                                         MerchantRoleReconciliationService merchantReconciliationService,
                                         ReconciliationRecoveryService recoveryService,
                                         ReconciliationAuditService auditService) {
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider is required");
        this.staffReconciliationService = Objects.requireNonNull(staffReconciliationService, "staffReconciliationService is required");
        this.merchantReconciliationService = Objects.requireNonNull(merchantReconciliationService, "merchantReconciliationService is required");
        this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService is required");
        this.auditService = Objects.requireNonNull(auditService, "auditService is required");
    }

    /**
     * Provisions a new staff profile and reconciles the approved staff role in Keycloak.
     */
    @PostMapping("/staff/provision")
    public ResponseEntity<StaffProfileResponse> provisionStaff(@RequestBody AdminProvisionStaffRequest request) {
        TrustedIdentity admin = identityProvider.currentIdentity();
        StaffProfileResponse response = staffReconciliationService.provisionStaff(admin, request.toCommand());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * Synchronizes an existing staff member's role with Keycloak based on their Accounts profile.
     */
    @PostMapping("/staff/{sub}/sync")
    public ResponseEntity<ReconciliationResult> syncStaffRole(@PathVariable("sub") String sub) {
        TrustedIdentity admin = identityProvider.currentIdentity();
        ReconciliationResult result = staffReconciliationService.syncStaffRole(admin, sub);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    /**
     * Suspends a staff member in Accounts and revokes their privileged staff role in Keycloak.
     */
    @PostMapping("/staff/{sub}/suspend")
    public ResponseEntity<ReconciliationResult> suspendStaff(@PathVariable("sub") String sub) {
        TrustedIdentity admin = identityProvider.currentIdentity();
        ReconciliationResult result = staffReconciliationService.suspendStaff(admin, sub, "ADMIN_INITIATED");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    /** Proposed Issue #122 command pending contract-owner confirmation. */
    @PostMapping("/staff/{sub}/activate")
    public ResponseEntity<ReconciliationResult> activateStaff(@PathVariable("sub") String sub) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(staffReconciliationService.activateStaff(identityProvider.currentIdentity(), sub, "ADMIN_INITIATED"));
    }

    /** Proposed Issue #122 command pending contract-owner confirmation. */
    @PostMapping("/staff/{sub}/deactivate")
    public ResponseEntity<ReconciliationResult> deactivateStaff(@PathVariable("sub") String sub) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(staffReconciliationService.deactivateStaff(identityProvider.currentIdentity(), sub, "ADMIN_INITIATED"));
    }

    /**
     * Synchronizes a merchant's Keycloak role based on their Accounts profile and active trigger policy.
     */
    @PostMapping("/merchant/{sub}/sync")
    public ResponseEntity<ReconciliationResult> syncMerchantRole(@PathVariable("sub") String sub) {
        TrustedIdentity admin = identityProvider.currentIdentity();
        ReconciliationResult result = merchantReconciliationService.reconcileMerchantRole(admin, sub);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    /**
     * Approves a merchant profile in Accounts and reconciles their MERCHANT role in Keycloak.
     */
    @PostMapping("/merchant/{id}/approve")
    public ResponseEntity<ReconciliationResult> approveMerchant(@PathVariable("id") UUID id) {
        TrustedIdentity admin = identityProvider.currentIdentity();
        ReconciliationResult result = merchantReconciliationService.approveMerchant(admin, id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    /**
     * Suspends a merchant in Accounts and revokes their MERCHANT role in Keycloak.
     */
    @PostMapping("/merchant/{id}/suspend")
    public ResponseEntity<ReconciliationResult> suspendMerchant(@PathVariable("id") UUID id) {
        TrustedIdentity admin = identityProvider.currentIdentity();
        ReconciliationResult result = merchantReconciliationService.suspendMerchant(admin, id, "ADMIN_INITIATED");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    /**
     * Retries a prior failed or pending reconciliation by re-evaluating authoritative source state.
     */
    @PostMapping("/retry/{auditId}")
    public ResponseEntity<ReconciliationResult> retryReconciliation(@PathVariable("auditId") UUID auditId) {
        TrustedIdentity admin = identityProvider.currentIdentity();
        ReconciliationResult result = recoveryService.retryReconciliation(auditId, admin);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    /**
     * Fetches audit history for a specific target Keycloak subject.
     */
    @GetMapping("/audit/{sub}")
    public ResponseEntity<List<IdentityReconciliationAudit>> getAuditHistory(@PathVariable("sub") String sub) {
        List<IdentityReconciliationAudit> history = auditService.findByTargetSub(sub);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(history);
    }
}
