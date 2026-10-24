package com.swiftdrop.accountsservice.merchant.credential;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.merchant.Merchant;
import com.swiftdrop.accountsservice.merchant.MerchantNotApprovedException;
import com.swiftdrop.accountsservice.merchant.MerchantRepository;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;
import com.swiftdrop.accountsservice.merchant.MerchantTenantAuthorizationProvider;
import com.swiftdrop.accountsservice.merchant.TenantAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MerchantApiKeyServiceTest {

    @Mock private MerchantApiKeyRepository apiKeyRepository;
    @Mock private MerchantRepository merchantRepository;
    @Mock private MerchantTenantAuthorizationProvider tenantAuthorizationProvider;

    private MerchantApiKeyGenerator keyGenerator;
    private MerchantApiKeyService service;

    private final UUID merchantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String keycloakSub = "sub-merchant-test";
    private final TrustedIdentity merchantIdentity = new TrustedIdentity(
            keycloakSub, "merchant@test.com", "Merchant User", List.of("MERCHANT")
    );

    @BeforeEach
    void setUp() {
        keyGenerator = new MerchantApiKeyGenerator();
        service = new MerchantApiKeyService(apiKeyRepository, merchantRepository, tenantAuthorizationProvider, keyGenerator);
    }

    @Test
    @DisplayName("1. Approved merchant can generate an API key with high entropy and one-way hashed persistence")
    void generateApiKey_approvedMerchant_success() {
        doNothing().when(tenantAuthorizationProvider).requireApprovedTenantAccess(merchantIdentity, merchantId);
        when(apiKeyRepository.countActiveKeys(eq(merchantId), any(Instant.class))).thenReturn(0L);

        var request = new CreateApiKeyRequest(
                "Warehouse Automated Dispenser",
                List.of("parcels:create", "parcels:read"),
                90
        );

        var response = service.generateApiKey(merchantIdentity, merchantId, request);

        assertThat(response).isNotNull();
        assertThat(response.keyId()).isNotNull();
        assertThat(response.name()).isEqualTo("Warehouse Automated Dispenser");
        assertThat(response.plaintextKey()).startsWith("swd_live_");
        assertThat(response.plaintextKey().length()).isEqualTo(41); // "swd_live_" (9) + 32 base62
        assertThat(response.keyPrefix()).isEqualTo(response.plaintextKey().substring(0, 13));
        assertThat(response.scopes()).containsExactly("parcels:create", "parcels:read");
        assertThat(response.expiresAt()).isAfter(response.createdAt());

        // Verify entity persisted in repository
        ArgumentCaptor<MerchantApiKey> captor = ArgumentCaptor.forClass(MerchantApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        MerchantApiKey saved = captor.getValue();

        assertThat(saved.getId()).isEqualTo(response.keyId());
        assertThat(saved.getMerchantId()).isEqualTo(merchantId);
        assertThat(saved.getName()).isEqualTo("Warehouse Automated Dispenser");
        assertThat(saved.getKeyPrefix()).isEqualTo(response.keyPrefix());
        // Verify key_digest is SHA-256 (64 hex characters) and NOT the plaintext key
        assertThat(saved.getKeyDigest()).matches("^[0-9a-f]{64}$");
        assertThat(saved.getKeyDigest()).isNotEqualTo(response.plaintextKey());
        assertThat(saved.isRevoked()).isFalse();
        assertThat(saved.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("2. Key rotation limit: Attempting to create 4th active key throws ApiKeyLimitExceededException")
    void generateApiKey_limitExceeded_throwsConflict() {
        doNothing().when(tenantAuthorizationProvider).requireApprovedTenantAccess(merchantIdentity, merchantId);
        when(apiKeyRepository.countActiveKeys(eq(merchantId), any(Instant.class))).thenReturn(3L);

        var request = new CreateApiKeyRequest("Fourth Key", List.of("parcels:create"), 30);

        assertThatThrownBy(() -> service.generateApiKey(merchantIdentity, merchantId, request))
                .isInstanceOf(ApiKeyLimitExceededException.class)
                .hasMessageContaining("maximum active API key limit of 3");

        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("3. Invalid scope is rejected with InvalidApiKeyScopeException")
    void generateApiKey_invalidScope_throwsException() {
        doNothing().when(tenantAuthorizationProvider).requireApprovedTenantAccess(merchantIdentity, merchantId);

        var request = new CreateApiKeyRequest("Key With Illegal Scope", List.of("parcels:create", "admin:all"), 30);

        assertThatThrownBy(() -> service.generateApiKey(merchantIdentity, merchantId, request))
                .isInstanceOf(InvalidApiKeyScopeException.class)
                .hasMessageContaining("admin:all");

        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("4. Blank key name is rejected with InvalidApiKeyRequestException")
    void generateApiKey_blankName_throwsException() {
        doNothing().when(tenantAuthorizationProvider).requireApprovedTenantAccess(merchantIdentity, merchantId);

        var request = new CreateApiKeyRequest("   ", List.of("parcels:create"), 30);

        assertThatThrownBy(() -> service.generateApiKey(merchantIdentity, merchantId, request))
                .isInstanceOf(InvalidApiKeyRequestException.class)
                .hasMessageContaining("Key name cannot be blank");
    }

    @Test
    @DisplayName("5. Expiration out of bounds (< 1 or > 365) is rejected")
    void generateApiKey_invalidExpiration_throwsException() {
        doNothing().when(tenantAuthorizationProvider).requireApprovedTenantAccess(merchantIdentity, merchantId);

        var requestZero = new CreateApiKeyRequest("Zero Expiry", List.of("parcels:create"), 0);
        assertThatThrownBy(() -> service.generateApiKey(merchantIdentity, merchantId, requestZero))
                .isInstanceOf(InvalidApiKeyRequestException.class)
                .hasMessageContaining("expiresInDays must be between 1 and 365");

        var requestOver = new CreateApiKeyRequest("Over Expiry", List.of("parcels:create"), 366);
        assertThatThrownBy(() -> service.generateApiKey(merchantIdentity, merchantId, requestOver))
                .isInstanceOf(InvalidApiKeyRequestException.class)
                .hasMessageContaining("expiresInDays must be between 1 and 365");
    }

    @Test
    @DisplayName("6. PENDING merchant cannot generate credentials (fail closed)")
    void generateApiKey_pendingMerchant_denied() {
        doThrow(new MerchantNotApprovedException(MerchantStatus.PENDING, "Merchant operation requires APPROVED status. Current status: PENDING"))
                .when(tenantAuthorizationProvider).requireApprovedTenantAccess(merchantIdentity, merchantId);

        var request = new CreateApiKeyRequest("Pending Key", List.of("parcels:create"), 90);

        assertThatThrownBy(() -> service.generateApiKey(merchantIdentity, merchantId, request))
                .isInstanceOf(MerchantNotApprovedException.class)
                .hasMessageContaining("PENDING");

        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("7. SUSPENDED merchant cannot generate credentials")
    void generateApiKey_suspendedMerchant_denied() {
        doThrow(new MerchantNotApprovedException(MerchantStatus.SUSPENDED, "Merchant operation requires APPROVED status. Current status: SUSPENDED"))
                .when(tenantAuthorizationProvider).requireApprovedTenantAccess(merchantIdentity, merchantId);

        var request = new CreateApiKeyRequest("Suspended Key", List.of("parcels:create"), 90);

        assertThatThrownBy(() -> service.generateApiKey(merchantIdentity, merchantId, request))
                .isInstanceOf(MerchantNotApprovedException.class)
                .hasMessageContaining("SUSPENDED");
    }

    @Test
    @DisplayName("8. Cross-tenant credential issuance is denied (Merchant A cannot create key for Merchant B)")
    void generateApiKey_wrongTenant_denied() {
        UUID otherMerchantId = UUID.randomUUID();
        doThrow(new TenantAccessDeniedException("Cross-tenant access denied"))
                .when(tenantAuthorizationProvider).requireApprovedTenantAccess(merchantIdentity, otherMerchantId);

        var request = new CreateApiKeyRequest("Impersonation Key", List.of("parcels:create"), 90);

        assertThatThrownBy(() -> service.generateApiKey(merchantIdentity, otherMerchantId, request))
                .isInstanceOf(TenantAccessDeniedException.class)
                .hasMessageContaining("Cross-tenant access denied");
    }

    @Test
    @DisplayName("9. Metadata listing returns safe summaries without plaintext or cryptographic digest")
    void listApiKeys_returnsSafeMetadata() {
        doNothing().when(tenantAuthorizationProvider).requireTenantAccess(merchantIdentity, merchantId);

        UUID key1Id = UUID.randomUUID();
        UUID key2Id = UUID.randomUUID();
        Instant now = Instant.now();

        var key1 = new MerchantApiKey(key1Id, merchantId, "Primary Key", "swd_live_7a8b",
                "sha256-digest-value-1", List.of("parcels:create"), now.minus(5, ChronoUnit.DAYS), now.plus(85, ChronoUnit.DAYS));
        var key2 = new MerchantApiKey(key2Id, merchantId, "Secondary Key", "swd_live_9c0d",
                "sha256-digest-value-2", List.of("parcels:read"), now.minus(10, ChronoUnit.DAYS), now.plus(80, ChronoUnit.DAYS));
        key2.revoke(now.minus(1, ChronoUnit.DAYS));

        when(apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId)).thenReturn(List.of(key1, key2));

        var response = service.listApiKeys(merchantIdentity, merchantId);

        assertThat(response.keys()).hasSize(2);

        var item1 = response.keys().get(0);
        assertThat(item1.keyId()).isEqualTo(key1Id);
        assertThat(item1.name()).isEqualTo("Primary Key");
        assertThat(item1.keyPrefix()).isEqualTo("swd_live_7a8b");
        assertThat(item1.scopes()).containsExactly("parcels:create");
        assertThat(item1.isRevoked()).isFalse();

        var item2 = response.keys().get(1);
        assertThat(item2.keyId()).isEqualTo(key2Id);
        assertThat(item2.name()).isEqualTo("Secondary Key");
        assertThat(item2.isRevoked()).isTrue();

        // Verification: ensure no digest leaks into metadata
        assertThat(item1.toString()).doesNotContain("sha256-digest-value-1");
        assertThat(item2.toString()).doesNotContain("sha256-digest-value-2");
    }

    @Test
    @DisplayName("10. Authorized owner can durably revoke an API key")
    void revokeApiKey_success() {
        doNothing().when(tenantAuthorizationProvider).requireTenantAccess(merchantIdentity, merchantId);

        UUID keyId = UUID.randomUUID();
        Instant now = Instant.now();
        var key = new MerchantApiKey(keyId, merchantId, "To Revoke", "swd_live_1234",
                "digest-1234", List.of("parcels:create"), now.minus(1, ChronoUnit.DAYS), now.plus(89, ChronoUnit.DAYS));

        when(apiKeyRepository.findByIdAndMerchantId(keyId, merchantId)).thenReturn(Optional.of(key));

        service.revokeApiKey(merchantIdentity, merchantId, keyId);

        assertThat(key.isRevoked()).isTrue();
        assertThat(key.getRevokedAt()).isNotNull();
        verify(apiKeyRepository).save(key);
    }

    @Test
    @DisplayName("11. Revoking an already revoked key is safe and idempotent")
    void revokeApiKey_alreadyRevoked_idempotent() {
        doNothing().when(tenantAuthorizationProvider).requireTenantAccess(merchantIdentity, merchantId);

        UUID keyId = UUID.randomUUID();
        Instant now = Instant.now();
        var key = new MerchantApiKey(keyId, merchantId, "Already Revoked", "swd_live_1234",
                "digest-1234", List.of("parcels:create"), now.minus(2, ChronoUnit.DAYS), now.plus(88, ChronoUnit.DAYS));
        key.revoke(now.minus(1, ChronoUnit.DAYS));

        when(apiKeyRepository.findByIdAndMerchantId(keyId, merchantId)).thenReturn(Optional.of(key));

        service.revokeApiKey(merchantIdentity, merchantId, keyId);

        // Does not call save again because it is already revoked
        verify(apiKeyRepository, never()).save(key);
    }

    @Test
    @DisplayName("12. Revoking non-existent key throws ApiKeyNotFoundException")
    void revokeApiKey_notFound_throwsException() {
        doNothing().when(tenantAuthorizationProvider).requireTenantAccess(merchantIdentity, merchantId);

        UUID missingKeyId = UUID.randomUUID();
        when(apiKeyRepository.findByIdAndMerchantId(missingKeyId, merchantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeApiKey(merchantIdentity, merchantId, missingKeyId))
                .isInstanceOf(ApiKeyNotFoundException.class)
                .hasMessageContaining("API key not found");
    }

    @Test
    @DisplayName("13. Key validation: Valid key for APPROVED merchant succeeds")
    void validateKeyDigest_validKeyApprovedMerchant_succeeds() {
        UUID keyId = UUID.randomUUID();
        Instant now = Instant.now();
        String secret = "swd_live_ValidKeySecretForTest123456789";
        String prefix = secret.substring(0, 13);
        String digest = keyGenerator.hashKey(secret);

        var key = new MerchantApiKey(keyId, merchantId, "Prod Key", prefix,
                digest, List.of("parcels:create", "parcels:read"), now.minus(1, ChronoUnit.DAYS), now.plus(89, ChronoUnit.DAYS));

        var merchant = new Merchant(
                merchantId, UUID.randomUUID(), keycloakSub,
                "Store Direct LK", "PV-123456", MerchantStatus.APPROVED, now.minus(30, ChronoUnit.DAYS), now
        );

        when(apiKeyRepository.findByKeyDigest(digest)).thenReturn(Optional.of(key));
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));

        var result = service.validateKeyDigest(prefix, digest);

        assertThat(result.isValid()).isTrue();
        assertThat(result.merchantId()).isEqualTo(merchantId);
        assertThat(result.businessName()).isEqualTo("Store Direct LK");
        assertThat(result.status()).isEqualTo(MerchantStatus.APPROVED);
        assertThat(result.scopes()).containsExactly("parcels:create", "parcels:read");
        assertThat(result.rejectionReason()).isNull();
    }

    @Test
    @DisplayName("14. Key validation: Revoked key is rejected with KEY_REVOKED")
    void validateKeyDigest_revokedKey_rejected() {
        UUID keyId = UUID.randomUUID();
        Instant now = Instant.now();
        String prefix = "swd_live_Revk";
        String digest = "digest-revoked";

        var key = new MerchantApiKey(keyId, merchantId, "Revoked Key", prefix,
                digest, List.of("parcels:create"), now.minus(5, ChronoUnit.DAYS), now.plus(85, ChronoUnit.DAYS));
        key.revoke(now.minus(1, ChronoUnit.DAYS));

        when(apiKeyRepository.findByKeyDigest(digest)).thenReturn(Optional.of(key));

        var result = service.validateKeyDigest(prefix, digest);

        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("API key has been revoked");
    }

    @Test
    @DisplayName("15. Key validation: Expired key is rejected")
    void validateKeyDigest_expiredKey_rejected() {
        UUID keyId = UUID.randomUUID();
        Instant now = Instant.now();
        String prefix = "swd_live_Expr";
        String digest = "digest-expired";

        var key = new MerchantApiKey(keyId, merchantId, "Expired Key", prefix,
                digest, List.of("parcels:create"), now.minus(90, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));

        when(apiKeyRepository.findByKeyDigest(digest)).thenReturn(Optional.of(key));

        var result = service.validateKeyDigest(prefix, digest);

        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("API key has expired");
    }

    @Test
    @DisplayName("16. Key validation: Suspended merchant returns unapproved status")
    void validateKeyDigest_suspendedMerchant_returnsUnapproved() {
        UUID keyId = UUID.randomUUID();
        Instant now = Instant.now();
        String prefix = "swd_live_Susp";
        String digest = "digest-suspended";

        var key = new MerchantApiKey(keyId, merchantId, "Suspended Merchant Key", prefix,
                digest, List.of("parcels:create"), now.minus(1, ChronoUnit.DAYS), now.plus(89, ChronoUnit.DAYS));

        var merchant = new Merchant(
                merchantId, UUID.randomUUID(), keycloakSub,
                "Store Direct LK", "PV-123456", MerchantStatus.SUSPENDED, now.minus(30, ChronoUnit.DAYS), now
        );

        when(apiKeyRepository.findByKeyDigest(digest)).thenReturn(Optional.of(key));
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));

        var result = service.validateKeyDigest(prefix, digest);

        assertThat(result.isValid()).isFalse();
        assertThat(result.status()).isEqualTo(MerchantStatus.SUSPENDED);
        assertThat(result.rejectionReason()).contains("SUSPENDED");
    }

    @Test
    @DisplayName("17. Cryptographic entropy: 100 generated keys produce 100 distinct secrets and digests with no collision")
    void keyGenerator_entropy_noCollisions() {
        Set<String> secrets = new HashSet<>();
        Set<String> digests = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            var gen = keyGenerator.generateSecret();
            assertThat(gen.plaintextKey()).startsWith("swd_live_");
            assertThat(gen.plaintextKey()).hasSize(41);
            assertThat(gen.keyPrefix()).hasSize(13);
            assertThat(gen.keyDigest()).hasSize(64);

            secrets.add(gen.plaintextKey());
            digests.add(gen.keyDigest());
        }

        assertThat(secrets).hasSize(100);
        assertThat(digests).hasSize(100);
    }

    @Test
    @DisplayName("18. Constant-time digest verification detects valid keys and rejects tampered keys")
    void keyGenerator_verifyDigest() {
        var gen = keyGenerator.generateSecret();
        assertThat(keyGenerator.verifyDigest(gen.plaintextKey(), gen.keyDigest())).isTrue();

        // Tampered secret
        String tampered = gen.plaintextKey().substring(0, 40) + (gen.plaintextKey().charAt(40) == 'A' ? 'B' : 'A');
        assertThat(keyGenerator.verifyDigest(tampered, gen.keyDigest())).isFalse();

        // Null checks
        assertThat(keyGenerator.verifyDigest(null, gen.keyDigest())).isFalse();
        assertThat(keyGenerator.verifyDigest(gen.plaintextKey(), null)).isFalse();
    }
}
