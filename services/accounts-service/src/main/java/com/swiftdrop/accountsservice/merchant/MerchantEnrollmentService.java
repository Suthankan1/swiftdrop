package com.swiftdrop.accountsservice.merchant;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service managing tenant-bound merchant business enrollment.
 * Binds verified authenticated caller identity to a new merchant organization
 * in PENDING approval state.
 */
@Service
@Transactional
public class MerchantEnrollmentService {

    private final MerchantRepository merchantRepository;
    private final AccountProfileRepository accountProfileRepository;

    public MerchantEnrollmentService(MerchantRepository merchantRepository,
                                   AccountProfileRepository accountProfileRepository) {
        this.merchantRepository = Objects.requireNonNull(merchantRepository, "MerchantRepository is required");
        this.accountProfileRepository = Objects.requireNonNull(accountProfileRepository, "AccountProfileRepository is required");
    }

    /**
     * Enrolls a new merchant tenant for an authenticated principal.
     * Enforces single-merchant-per-owner invariant, registration number uniqueness,
     * trusted identity binding, and server-controlled PENDING lifecycle state.
     *
     * @param identity verified caller identity from security context
     * @param request validated business registration request
     * @return persisted merchant tenant response matching MerchantProfileResponse contract
     */
    public MerchantProfileResponse enrollMerchant(TrustedIdentity identity, MerchantRegistrationRequest request) {
        if (identity == null || identity.subject() == null || identity.subject().isBlank()) {
            throw new IllegalArgumentException("Authenticated caller identity is required for merchant enrollment");
        }
        if (request == null) {
            throw new IllegalArgumentException("Registration request is required");
        }

        String businessName = request.businessName() != null ? request.businessName().strip() : "";
        String registrationNumber = request.registrationNumber() != null ? request.registrationNumber().strip() : "";

        if (businessName.length() < 3 || businessName.length() > 255) {
            throw new IllegalArgumentException("Business name must be between 3 and 255 characters");
        }
        if (registrationNumber.length() < 3 || registrationNumber.length() > 100) {
            throw new IllegalArgumentException("Registration number must be between 3 and 100 characters");
        }

        // 1. Conflict check: Caller already owns a merchant tenant
        if (merchantRepository.findByKeycloakSub(identity.subject()).isPresent()) {
            throw new MerchantConflictException("Authenticated user already owns a merchant tenant");
        }

        // 2. Conflict check: Registration number is already registered by another merchant
        if (merchantRepository.findByRegistrationNumber(registrationNumber).isPresent()) {
            throw new MerchantConflictException("Merchant registration number already exists: " + registrationNumber);
        }

        // 3. Resolve or JIT-provision AccountProfile for trusted subject
        AccountProfile accountProfile = accountProfileRepository.findByKeycloakSub(identity.subject())
                .orElseGet(() -> {
                    accountProfileRepository.insertIfAbsent(
                            identity.subject(),
                            identity.email() != null ? identity.email() : "",
                            identity.fullName() != null ? identity.fullName() : ""
                    );
                    return accountProfileRepository.findByKeycloakSub(identity.subject())
                            .orElseThrow(() -> new IllegalStateException("Failed to resolve or provision account profile for subject: " + identity.subject()));
                });

        // 4. Construct server-controlled merchant tenant with PENDING status
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();
        Merchant merchant = new Merchant(
                merchantId,
                accountProfile.getId(),
                identity.subject(),
                businessName,
                registrationNumber,
                MerchantStatus.PENDING,
                now,
                now
        );

        // 5. Persist with database constraint concurrency protection
        Merchant saved;
        try {
            saved = merchantRepository.save(merchant);
        } catch (DataIntegrityViolationException ex) {
            String combined = (ex.getMessage() != null ? ex.getMessage() : "") + " "
                    + (ex.getRootCause() != null && ex.getRootCause().getMessage() != null ? ex.getRootCause().getMessage() : "");
            if (combined.contains("uq_merchants_keycloak_sub")) {
                throw new MerchantConflictException("Authenticated user already owns a merchant tenant", ex);
            }
            if (combined.contains("uq_merchants_registration_number")) {
                throw new MerchantConflictException("Merchant registration number already exists: " + registrationNumber, ex);
            }
            throw ex;
        }

        return MerchantProfileResponse.fromEntity(saved);
    }
}
