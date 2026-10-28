package com.swiftdrop.communicationsservice.webhook.security;

import com.swiftdrop.communicationsservice.webhook.exception.WebhookSecretEncryptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSigningSecretServiceTest {

    private WebhookSigningSecretService secretService;

    @BeforeEach
    void setUp() {
        // Use deterministic test key derivation fallback
        secretService = new WebhookSigningSecretService("");
    }

    @Test
    @DisplayName("Generated secret starts with swd_whsec_ and contains 64 hex characters (256-bit entropy)")
    void generateSecret_ValidFormatAndEntropy() {
        String secret = secretService.generateSecret();
        assertThat(secret).startsWith("swd_whsec_");
        assertThat(secret).hasSize("swd_whsec_".length() + 64);
        assertThat(secret.substring("swd_whsec_".length())).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("Generated secrets are cryptographically unique")
    void generateSecret_UniqueValues() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String secret = secretService.generateSecret();
            assertThat(generated.add(secret)).isTrue();
        }
    }

    @Test
    @DisplayName("Encryption and decryption roundtrip preserves exact plaintext secret")
    void encryptAndDecrypt_Roundtrip() {
        String originalSecret = secretService.generateSecret();
        String secretRef = secretService.encryptSecret(originalSecret);

        assertThat(secretRef).startsWith("enc:v1:");
        assertThat(secretRef).doesNotContain(originalSecret); // Plaintext is not exposed

        String decrypted = secretService.decryptSecret(secretRef);
        assertThat(decrypted).isEqualTo(originalSecret);
    }

    @Test
    @DisplayName("Same plaintext encrypted twice produces different IVs and ciphertexts")
    void encryptTwice_ProducesDifferentCiphertext() {
        String secret = secretService.generateSecret();
        String ref1 = secretService.encryptSecret(secret);
        String ref2 = secretService.encryptSecret(secret);

        assertThat(ref1).isNotEqualTo(ref2);
        assertThat(secretService.decryptSecret(ref1)).isEqualTo(secret);
        assertThat(secretService.decryptSecret(ref2)).isEqualTo(secret);
    }

    @Test
    @DisplayName("Malformed secret reference fails decryption safely")
    void decrypt_MalformedRef_FailsSafely() {
        assertThatThrownBy(() -> secretService.decryptSecret("plain_unencrypted_secret"))
                .isInstanceOf(WebhookSecretEncryptionException.class)
                .hasMessageContaining("Invalid encrypted secret reference format");

        assertThatThrownBy(() -> secretService.decryptSecret("enc:v1:invalid_corrupted_data"))
                .isInstanceOf(WebhookSecretEncryptionException.class);
    }
}
