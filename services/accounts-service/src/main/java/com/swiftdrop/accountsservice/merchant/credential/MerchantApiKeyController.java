package com.swiftdrop.accountsservice.merchant.credential;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller exposing REST endpoints for merchant API key generation, listing, and revocation.
 * Protected by Spring Security and tenant authorization checks.
 * All responses include Cache-Control: no-store to prevent credential caching.
 */
@RestController
public class MerchantApiKeyController {

    private final CurrentIdentityProvider identityProvider;
    private final MerchantApiKeyService apiKeyService;

    public MerchantApiKeyController(CurrentIdentityProvider identityProvider,
                                    MerchantApiKeyService apiKeyService) {
        this.identityProvider = Objects.requireNonNull(identityProvider, "CurrentIdentityProvider is required");
        this.apiKeyService = Objects.requireNonNull(apiKeyService, "MerchantApiKeyService is required");
    }

    /**
     * Issues a new scoped machine API key for the specified merchant tenant.
     * The plaintext secret is returned once in the response body.
     */
    @PostMapping("/api/v1/merchants/{merchantId}/api-keys")
    public ResponseEntity<ApiKeyCreatedResponse> generateApiKey(
            @PathVariable UUID merchantId,
            @Valid @RequestBody CreateApiKeyRequest request) {
        var identity = identityProvider.currentIdentity();
        var response = apiKeyService.generateApiKey(identity, merchantId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * Lists credential metadata (key prefix, scopes, created/expiration timestamps, revocation status).
     * Strictly omits plaintext secrets and cryptographic hashes.
     */
    @GetMapping("/api/v1/merchants/{merchantId}/api-keys")
    public ResponseEntity<ApiKeyListResponse> listApiKeys(@PathVariable UUID merchantId) {
        var identity = identityProvider.currentIdentity();
        var response = apiKeyService.listApiKeys(identity, merchantId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * Immediately and durably revokes a specific merchant API key.
     */
    @DeleteMapping("/api/v1/merchants/{merchantId}/api-keys/{keyId}")
    public ResponseEntity<Void> revokeApiKey(
            @PathVariable UUID merchantId,
            @PathVariable UUID keyId) {
        var identity = identityProvider.currentIdentity();
        apiKeyService.revokeApiKey(identity, merchantId, keyId);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    /**
     * Alias endpoint for key revocation via POST for clients with firewall or method restrictions.
     */
    @PostMapping("/api/v1/merchants/{merchantId}/api-keys/{keyId}/revoke")
    public ResponseEntity<Void> revokeApiKeyPost(
            @PathVariable UUID merchantId,
            @PathVariable UUID keyId) {
        return revokeApiKey(merchantId, keyId);
    }
}
