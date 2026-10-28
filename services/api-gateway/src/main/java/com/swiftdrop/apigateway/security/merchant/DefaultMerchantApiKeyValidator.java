package com.swiftdrop.apigateway.security.merchant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Default merchant machine API-key validator.
 * Validates syntax, scopes, expiration, and merchant approval status.
 *
 * In production, authoritative validation is coordinated with Accounts & Partners.
 * If no external provider is registered, this validator operates on registered/configured keys,
 * failing closed on unknown or invalid credentials.
 */
@Component
public class DefaultMerchantApiKeyValidator implements MerchantApiKeyValidator {

    private static final Logger log = LoggerFactory.getLogger(DefaultMerchantApiKeyValidator.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^swd_live_[0-9a-zA-Z]{32}$");

    public record RegisteredMerchantKey(
            String rawApiKey,
            String merchantId,
            String keyId,
            Set<String> scopes,
            String merchantStatus,
            Instant expiresAt,
            boolean revoked
    ) {}

    private final Map<String, RegisteredMerchantKey> registeredKeys = new ConcurrentHashMap<>();

    public void registerKey(RegisteredMerchantKey key) {
        this.registeredKeys.put(key.rawApiKey(), key);
    }

    public void clearKeys() {
        this.registeredKeys.clear();
    }

    @Override
    public Mono<MerchantApiKeyValidationResult> validateKey(String rawApiKey, String requiredScope) {
        if (rawApiKey == null || !KEY_PATTERN.matcher(rawApiKey).matches()) {
            return Mono.just(MerchantApiKeyValidationResult.unauthenticated("Malformed API key format"));
        }

        String maskedKey = maskKey(rawApiKey);

        // Constant-time candidate lookup
        RegisteredMerchantKey matched = null;
        for (RegisteredMerchantKey candidate : registeredKeys.values()) {
            if (MessageDigest.isEqual(
                    candidate.rawApiKey().getBytes(StandardCharsets.UTF_8),
                    rawApiKey.getBytes(StandardCharsets.UTF_8))) {
                matched = candidate;
                break;
            }
        }

        if (matched == null) {
            log.warn("Merchant API key validation failed: key not found [prefix={}]", maskedKey);
            return Mono.just(MerchantApiKeyValidationResult.unauthenticated("Invalid merchant API key"));
        }

        if (matched.revoked()) {
            log.warn("Merchant API key validation failed: key is revoked [prefix={}]", maskedKey);
            return Mono.just(MerchantApiKeyValidationResult.unauthenticated("Merchant API key has been revoked"));
        }

        if (matched.expiresAt() != null && matched.expiresAt().isBefore(Instant.now())) {
            log.warn("Merchant API key validation failed: key is expired [prefix={}]", maskedKey);
            return Mono.just(MerchantApiKeyValidationResult.unauthenticated("Merchant API key has expired"));
        }

        // Check merchant business approval status
        if (!"APPROVED".equalsIgnoreCase(matched.merchantStatus())) {
            log.warn("Merchant API key rejected: merchant organization status is '{}' [merchantId={}]",
                    matched.merchantStatus(), matched.merchantId());
            return Mono.just(MerchantApiKeyValidationResult.forbidden(
                    matched.merchantId(),
                    matched.merchantStatus(),
                    "Merchant organization is not approved (status: " + matched.merchantStatus() + ")"
            ));
        }

        // Check scope
        if (requiredScope != null && !matched.scopes().contains(requiredScope)) {
            log.warn("Merchant API key rejected: insufficient scope [required={}, held={}]",
                    requiredScope, matched.scopes());
            return Mono.just(MerchantApiKeyValidationResult.forbidden(
                    matched.merchantId(),
                    matched.merchantStatus(),
                    "Insufficient scope for this operation"
            ));
        }

        String keyPrefix = rawApiKey.substring(0, 13);
        return Mono.just(MerchantApiKeyValidationResult.success(
                matched.merchantId(),
                matched.keyId(),
                keyPrefix,
                matched.scopes()
        ));
    }

    public static String maskKey(String rawKey) {
        if (rawKey == null || rawKey.length() < 13) {
            return "[REDACTED]";
        }
        return rawKey.substring(0, 13) + "...";
    }
}
