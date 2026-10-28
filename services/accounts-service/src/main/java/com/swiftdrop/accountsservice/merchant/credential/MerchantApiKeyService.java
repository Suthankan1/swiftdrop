package com.swiftdrop.accountsservice.merchant.credential;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.merchant.MerchantRepository;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;
import com.swiftdrop.accountsservice.merchant.MerchantTenantAuthorizationProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Service managing merchant API key lifecycle: generation, metadata listing, durable revocation,
 * active key rotation limits, and cryptographic verification against business approval state.
 */
@Service
@Transactional
public class MerchantApiKeyService implements MerchantApiKeyValidator {

    public static final int MAX_ACTIVE_KEYS_PER_MERCHANT = 3;
    public static final Set<String> ALLOWED_SCOPES = Set.of("parcels:create", "parcels:read", "webhooks:manage");

    private final MerchantApiKeyRepository apiKeyRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantTenantAuthorizationProvider tenantAuthorizationProvider;
    private final MerchantApiKeyGenerator keyGenerator;

    public MerchantApiKeyService(MerchantApiKeyRepository apiKeyRepository,
                                 MerchantRepository merchantRepository,
                                 MerchantTenantAuthorizationProvider tenantAuthorizationProvider,
                                 MerchantApiKeyGenerator keyGenerator) {
        this.apiKeyRepository = Objects.requireNonNull(apiKeyRepository, "MerchantApiKeyRepository is required");
        this.merchantRepository = Objects.requireNonNull(merchantRepository, "MerchantRepository is required");
        this.tenantAuthorizationProvider = Objects.requireNonNull(tenantAuthorizationProvider, "MerchantTenantAuthorizationProvider is required");
        this.keyGenerator = Objects.requireNonNull(keyGenerator, "MerchantApiKeyGenerator is required");
    }

    /**
     * Issues a new scoped machine API key for an approved merchant tenant.
     * The plaintext secret is returned once in the response and is never stored.
     */
    public ApiKeyCreatedResponse generateApiKey(TrustedIdentity identity, UUID merchantId, CreateApiKeyRequest request) {
        // Enforce merchant approval state and caller tenant ownership (fails closed if PENDING, SUSPENDED, REJECTED)
        tenantAuthorizationProvider.requireApprovedTenantAccess(identity, merchantId);

        // Validate request parameters
        validateCreateRequest(request);

        // Enforce maximum active key rotation limit (DEC-MER-04)
        Instant now = Instant.now();
        long activeKeysCount = apiKeyRepository.countActiveKeys(merchantId, now);
        if (activeKeysCount >= MAX_ACTIVE_KEYS_PER_MERCHANT) {
            throw new ApiKeyLimitExceededException(
                    "Merchant tenant has reached maximum active API key limit of " + MAX_ACTIVE_KEYS_PER_MERCHANT +
                    ". Revoke an unused key before issuing a new one."
            );
        }

        // Cryptographically generate secret, prefix, and one-way SHA-256 digest
        var generated = keyGenerator.generateSecret();
        Instant expiresAt = now.plus(request.resolvedExpiresInDays(), ChronoUnit.DAYS);
        UUID keyId = UUID.randomUUID();

        // Persist only metadata and digest (no plaintext)
        var entity = new MerchantApiKey(
                keyId,
                merchantId,
                request.name().trim(),
                generated.keyPrefix(),
                generated.keyDigest(),
                request.scopes(),
                now,
                expiresAt
        );
        apiKeyRepository.save(entity);

        return new ApiKeyCreatedResponse(
                keyId,
                entity.getName(),
                entity.getKeyPrefix(),
                generated.plaintextKey(),
                entity.getScopes(),
                now,
                expiresAt
        );
    }

    /**
     * Lists active and revoked credential metadata for an authorized merchant tenant.
     * Never returns plaintext secrets or cryptographic digests.
     */
    @Transactional(readOnly = true)
    public ApiKeyListResponse listApiKeys(TrustedIdentity identity, UUID merchantId) {
        tenantAuthorizationProvider.requireTenantAccess(identity, merchantId);

        List<ApiKeySummaryItem> items = apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId)
                .stream()
                .map(ApiKeySummaryItem::fromEntity)
                .toList();

        return new ApiKeyListResponse(items);
    }

    /**
     * Durably revokes an API key. Safe for repeated revocation.
     */
    public void revokeApiKey(TrustedIdentity identity, UUID merchantId, UUID keyId) {
        tenantAuthorizationProvider.requireTenantAccess(identity, merchantId);

        var key = apiKeyRepository.findByIdAndMerchantId(keyId, merchantId)
                .orElseThrow(() -> new ApiKeyNotFoundException("API key not found: " + keyId + " for merchant: " + merchantId));

        if (!key.isRevoked()) {
            key.revoke(Instant.now());
            apiKeyRepository.save(key);
        }
    }

    /**
     * Validates an API key digest and evaluates caller merchant approval status.
     * Conforms to SERVICE_INTERFACE_MATRIX IF-GRPC-02 and DEC-MER-03.
     */
    @Override
    @Transactional(readOnly = true)
    public MerchantKeyValidationResult validateKeyDigest(String keyPrefix, String keyDigest) {
        if (keyDigest == null || keyDigest.isBlank()) {
            return MerchantKeyValidationResult.invalid("Key digest is missing or empty");
        }

        var keyOpt = apiKeyRepository.findByKeyDigest(keyDigest);
        if (keyOpt.isEmpty()) {
            return MerchantKeyValidationResult.invalid("API key not recognized");
        }

        var key = keyOpt.get();

        if (keyPrefix != null && !keyPrefix.isBlank() && !key.getKeyPrefix().equals(keyPrefix)) {
            return MerchantKeyValidationResult.invalid("API key prefix mismatch");
        }

        if (key.isRevoked()) {
            return MerchantKeyValidationResult.invalid("API key has been revoked");
        }

        Instant now = Instant.now();
        if (key.isExpired(now)) {
            return MerchantKeyValidationResult.invalid("API key has expired");
        }

        var merchantOpt = merchantRepository.findById(key.getMerchantId());
        if (merchantOpt.isEmpty()) {
            return MerchantKeyValidationResult.invalid("Merchant tenant not found");
        }

        var merchant = merchantOpt.get();
        if (merchant.getStatus() != MerchantStatus.APPROVED) {
            return MerchantKeyValidationResult.unapprovedMerchant(
                    merchant.getId(),
                    merchant.getBusinessName(),
                    merchant.getStatus(),
                    key.getScopes(),
                    "Merchant organization is not in APPROVED status. Current status: " + merchant.getStatus()
            );
        }

        return MerchantKeyValidationResult.valid(
                merchant.getId(),
                merchant.getBusinessName(),
                merchant.getStatus(),
                key.getScopes()
        );
    }

    /**
     * Validates a raw plaintext API key (extracts prefix and computes SHA-256 digest).
     */
    @Transactional(readOnly = true)
    public MerchantKeyValidationResult validatePlaintextKey(String plaintextKey) {
        if (plaintextKey == null || !plaintextKey.startsWith(MerchantApiKeyGenerator.KEY_PREFIX_CONSTANT)) {
            return MerchantKeyValidationResult.invalid("Malformed or unrecognized API key format");
        }
        String prefix = plaintextKey.substring(0, Math.min(13, plaintextKey.length()));
        String digest = keyGenerator.hashKey(plaintextKey);
        return validateKeyDigest(prefix, digest);
    }

    private void validateCreateRequest(CreateApiKeyRequest request) {
        if (request == null) {
            throw new InvalidApiKeyRequestException("Request body is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new InvalidApiKeyRequestException("Key name cannot be blank");
        }
        if (request.scopes() == null || request.scopes().isEmpty()) {
            throw new InvalidApiKeyRequestException("At least one scope is required");
        }
        for (String scope : request.scopes()) {
            if (scope == null || !ALLOWED_SCOPES.contains(scope.trim())) {
                throw new InvalidApiKeyScopeException(
                        "Invalid scope: '" + scope + "'. Allowed scopes: " + ALLOWED_SCOPES
                );
            }
        }
        if (request.expiresInDays() != null && (request.expiresInDays() < 1 || request.expiresInDays() > 365)) {
            throw new InvalidApiKeyRequestException("expiresInDays must be between 1 and 365 days");
        }
    }
}
