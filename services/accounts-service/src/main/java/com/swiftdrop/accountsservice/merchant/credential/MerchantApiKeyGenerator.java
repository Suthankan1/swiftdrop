package com.swiftdrop.accountsservice.merchant.credential;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * Cryptographic token generator and hasher for Merchant API keys.
 * Uses SecureRandom with Base62 entropy and SHA-256 one-way hashing with constant-time equality checks.
 */
@Component
public class MerchantApiKeyGenerator {

    public static final String KEY_PREFIX_CONSTANT = "swd_live_";
    private static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int RANDOM_TOKEN_LENGTH = 32;
    private static final int PREFIX_TOTAL_LENGTH = 13; // "swd_live_" (9) + 4 chars = 13 chars

    private final SecureRandom secureRandom = new SecureRandom();

    public record GeneratedSecret(String plaintextKey, String keyPrefix, String keyDigest) {
        public GeneratedSecret {
            Objects.requireNonNull(plaintextKey, "plaintextKey cannot be null");
            Objects.requireNonNull(keyPrefix, "keyPrefix cannot be null");
            Objects.requireNonNull(keyDigest, "keyDigest cannot be null");
        }
    }

    /**
     * Generates a new cryptographically secure high-entropy API key secret, extracts its prefix,
     * and computes its one-way SHA-256 digest.
     */
    public GeneratedSecret generateSecret() {
        StringBuilder sb = new StringBuilder(KEY_PREFIX_CONSTANT);
        for (int i = 0; i < RANDOM_TOKEN_LENGTH; i++) {
            int index = secureRandom.nextInt(BASE62_CHARS.length());
            sb.append(BASE62_CHARS.charAt(index));
        }
        String plaintext = sb.toString();
        String prefix = plaintext.substring(0, Math.min(PREFIX_TOTAL_LENGTH, plaintext.length()));
        String digest = hashKey(plaintext);
        return new GeneratedSecret(plaintext, prefix, digest);
    }

    /**
     * Computes the SHA-256 hex digest of the given plaintext API key secret.
     */
    public String hashKey(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Cannot hash null or blank secret");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in runtime", e);
        }
    }

    /**
     * Performs a constant-time comparison of two digests to mitigate timing attacks.
     */
    public boolean verifyDigest(String providedSecret, String storedDigest) {
        if (providedSecret == null || storedDigest == null) {
            return false;
        }
        String computedDigest = hashKey(providedSecret);
        byte[] a = computedDigest.getBytes(StandardCharsets.UTF_8);
        byte[] b = storedDigest.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
