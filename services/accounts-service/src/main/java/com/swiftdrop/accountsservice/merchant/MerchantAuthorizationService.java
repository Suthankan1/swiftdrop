package com.swiftdrop.accountsservice.merchant;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service implementing MerchantApprovalProvider and MerchantTenantAuthorizationProvider.
 * Enforces fail-closed tenant boundary checks and decoupled business approval lifecycle.
 */
@Service
@Transactional(readOnly = true)
public class MerchantAuthorizationService implements MerchantApprovalProvider, MerchantTenantAuthorizationProvider {

    private final MerchantRepository merchantRepository;

    public MerchantAuthorizationService(MerchantRepository merchantRepository) {
        this.merchantRepository = Objects.requireNonNull(merchantRepository, "MerchantRepository is required");
    }

    @Override
    public Optional<MerchantStatus> getApprovalStatus(UUID merchantId) {
        if (merchantId == null) {
            return Optional.empty();
        }
        return merchantRepository.findById(merchantId).map(Merchant::getStatus);
    }

    @Override
    public Optional<MerchantStatus> getApprovalStatusBySubject(String keycloakSub) {
        if (keycloakSub == null || keycloakSub.isBlank()) {
            return Optional.empty();
        }
        return merchantRepository.findByKeycloakSub(keycloakSub).map(Merchant::getStatus);
    }

    @Override
    public boolean isApproved(UUID merchantId) {
        if (merchantId == null) {
            return false;
        }
        return merchantRepository.findById(merchantId)
                .map(m -> m.getStatus().isApproved())
                .orElse(false);
    }

    @Override
    public boolean isApproved(String keycloakSub) {
        if (keycloakSub == null || keycloakSub.isBlank()) {
            return false;
        }
        return merchantRepository.findByKeycloakSub(keycloakSub)
                .map(m -> m.getStatus().isApproved())
                .orElse(false);
    }

    @Override
    public TenantAuthorizationDecision authorizeTenantAccess(TrustedIdentity identity, UUID targetMerchantId) {
        if (identity == null) {
            return TenantAuthorizationDecision.denied("Caller identity is missing");
        }
        if (targetMerchantId == null) {
            return TenantAuthorizationDecision.denied("Target merchant ID is missing");
        }

        var merchantOpt = merchantRepository.findById(targetMerchantId);
        if (merchantOpt.isEmpty()) {
            return TenantAuthorizationDecision.denied(targetMerchantId, null, "Merchant tenant not found");
        }

        var merchant = merchantOpt.get();

        // Platform administrators have oversight access
        if (identity.roles().contains("ADMIN")) {
            return TenantAuthorizationDecision.allowed(targetMerchantId, merchant.getStatus());
        }

        // Must hold the MERCHANT role
        if (!identity.roles().contains("MERCHANT")) {
            return TenantAuthorizationDecision.denied(targetMerchantId, merchant.getStatus(),
                    "Caller lacks MERCHANT or ADMIN role");
        }

        // Strict subject-to-tenant binding: caller's verified keycloakSub must match merchant's owner
        if (!merchant.getKeycloakSub().equals(identity.subject())) {
            return TenantAuthorizationDecision.denied(targetMerchantId, merchant.getStatus(),
                    "Cross-tenant access forbidden: Caller does not own the requested merchant tenant");
        }

        return TenantAuthorizationDecision.allowed(targetMerchantId, merchant.getStatus());
    }

    @Override
    public void requireTenantAccess(TrustedIdentity identity, UUID targetMerchantId) {
        if (identity == null) {
            throw new TenantAccessDeniedException("Authenticated identity is required");
        }
        if (targetMerchantId == null) {
            throw new TenantAccessDeniedException("Target merchant ID is required");
        }

        var merchant = merchantRepository.findById(targetMerchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + targetMerchantId));

        if (identity.roles().contains("ADMIN")) {
            return;
        }

        if (!identity.roles().contains("MERCHANT")) {
            throw new TenantAccessDeniedException("Access denied: Caller lacks MERCHANT role");
        }

        if (!merchant.getKeycloakSub().equals(identity.subject())) {
            throw new TenantAccessDeniedException("Cross-tenant access denied: Caller does not own merchant tenant " + targetMerchantId);
        }
    }

    @Override
    public void requireApprovedTenantAccess(TrustedIdentity identity, UUID targetMerchantId) {
        requireTenantAccess(identity, targetMerchantId);

        var merchant = merchantRepository.findById(targetMerchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + targetMerchantId));

        if (!merchant.getStatus().isApproved()) {
            throw new MerchantNotApprovedException(
                    merchant.getStatus(),
                    "Merchant operation requires APPROVED status. Current status: " + merchant.getStatus()
            );
        }
    }

    @Override
    public Optional<MerchantRecord> resolveMerchantForIdentity(TrustedIdentity identity) {
        if (identity == null || identity.subject() == null || identity.subject().isBlank()) {
            return Optional.empty();
        }
        return merchantRepository.findByKeycloakSub(identity.subject())
                .map(MerchantRecord::fromEntity);
    }

    @Override
    public Optional<MerchantRecord> resolveMerchantById(UUID merchantId) {
        if (merchantId == null) {
            return Optional.empty();
        }
        return merchantRepository.findById(merchantId)
                .map(MerchantRecord::fromEntity);
    }
}
