package com.swiftdrop.communicationsservice.webhook.security;

import com.swiftdrop.communicationsservice.webhook.exception.WebhookSecretEncryptionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Manages webhook HMAC signing secrets:
 * 1. Cryptographically secure random generation (256-bit entropy).
 * 2. Protected storage via authenticated AES-256-GCM encryption at rest.
 * 3. Decryption support for future outbound webhook signing workers (Issue #142).
 * 4. Strict hygiene: plaintext is never logged, exposed via GET, or persisted in unencrypted DB columns.
 */
@Service
public class WebhookSigningSecretService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSigningSecretService.class);

    private static final String SECRET_PREFIX = "swd_whsec_";
    private static final int SECRET_RANDOM_BYTES = 32; // 256-bit entropy
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String ENCRYPTION_PREFIX = "enc:v1:";

    private final SecretKey masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebhookSigningSecretService(
            @Value("${swiftdrop.webhooks.encryption-key:}") String configuredKey) {
        this.masterKey = resolveMasterKey(configuredKey);
    }

    private SecretKey resolveMasterKey(String configuredKey) {
        String keyMaterial = configuredKey;
        if (keyMaterial == null || keyMaterial.isBlank()) {
            keyMaterial = System.getenv("SWIFTDROP_WEBHOOK_SECRET_KEY");
        }

        byte[] keyBytes;
        if (keyMaterial != null && !keyMaterial.isBlank()) {
            String trimmed = keyMaterial.trim();
            if (trimmed.length() == 64 && trimmed.matches("^[0-9a-fA-F]+$")) {
                keyBytes = HexFormat.of().parseHex(trimmed);
            } else {
                try {
                    byte[] decoded = Base64.getDecoder().decode(trimmed);
                    if (decoded.length == 32) {
                        keyBytes = decoded;
                    } else {
                        keyBytes = sha256(trimmed.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IllegalArgumentException ex) {
                    keyBytes = sha256(trimmed.getBytes(StandardCharsets.UTF_8));
                }
            }
        } else {
            // Secure deterministic fallback for test and local development
            log.warn("No webhook master encryption key configured; utilizing local test key derivation.");
            keyBytes = sha256("swiftdrop-local-test-master-key-seed".getBytes(StandardCharsets.UTF_8));
        }

        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    /**
     * Generates a new cryptographically secure signing secret with format:
     * swd_whsec_<64 hex chars>
     */
    public String generateSecret() {
        byte[] randomBytes = new byte[SECRET_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        return SECRET_PREFIX + HexFormat.of().formatHex(randomBytes);
    }

    /**
     * Encrypts plaintext signing secret using AES-256-GCM.
     * Stored format: enc:v1:<base64url_iv>:<base64url_ciphertext_tag>
     */
    public String encryptSecret(String plaintextSecret) {
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            throw new IllegalArgumentException("Secret to encrypt must not be null or blank");
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] plaintextBytes = plaintextSecret.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertextWithTag = cipher.doFinal(plaintextBytes);

            String ivB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(iv);
            String ctB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertextWithTag);

            return ENCRYPTION_PREFIX + ivB64 + ":" + ctB64;
        } catch (Exception ex) {
            log.error("Failed to encrypt webhook signing secret", ex);
            throw new WebhookSecretEncryptionException("Failed to encrypt webhook signing secret material", ex);
        }
    }

    /**
     * Decrypts encrypted secret reference using AES-256-GCM.
     * Intended for outbound webhook signing dispatch workers.
     */
    public String decryptSecret(String secretRef) {
        if (secretRef == null || !secretRef.startsWith(ENCRYPTION_PREFIX)) {
            throw new WebhookSecretEncryptionException("Invalid encrypted secret reference format");
        }

        try {
            String payload = secretRef.substring(ENCRYPTION_PREFIX.length());
            String[] parts = payload.split(":");
            if (parts.length != 2) {
                throw new WebhookSecretEncryptionException("Malformed encrypted secret reference");
            }

            byte[] iv = Base64.getUrlDecoder().decode(parts[0]);
            byte[] ciphertextWithTag = Base64.getUrlDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] decrypted = cipher.doFinal(ciphertextWithTag);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("Failed to decrypt webhook signing secret reference", ex);
            throw new WebhookSecretEncryptionException("Failed to decrypt webhook signing secret", ex);
        }
    }
}
