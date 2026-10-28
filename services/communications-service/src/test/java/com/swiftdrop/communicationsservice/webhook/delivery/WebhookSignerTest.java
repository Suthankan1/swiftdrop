package com.swiftdrop.communicationsservice.webhook.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignerTest {

    private DefaultWebhookSigner signer;
    private static final String TEST_SECRET = "swd_whsec_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TEST_PAYLOAD = "{\"eventId\":\"f47ac10b-58cc-4372-a567-0e02b2c3d479\",\"eventType\":\"parcel.status.changed.v1\"}";

    @BeforeEach
    void setUp() {
        signer = new DefaultWebhookSigner();
    }

    @Test
    @DisplayName("Generate signature produces correct format t={epoch},v1={hex} with 64-char lowercase hex digest")
    void generateSignature_ProducesCorrectFormat() {
        Instant timestamp = Instant.ofEpochSecond(1773000000L);
        WebhookSignature signature = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, timestamp);

        assertThat(signature.timestamp()).isEqualTo(1773000000L);
        assertThat(signature.hexDigest()).matches("^[0-9a-f]{64}$");
        assertThat(signature.headerValue()).isEqualTo("t=1773000000,v1=" + signature.hexDigest());
    }

    @Test
    @DisplayName("Signature is deterministic for identical payload, timestamp, and secret")
    void generateSignature_Deterministic() {
        Instant timestamp = Instant.ofEpochSecond(1773000000L);
        WebhookSignature sig1 = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, timestamp);
        WebhookSignature sig2 = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, timestamp);

        assertThat(sig1.hexDigest()).isEqualTo(sig2.hexDigest());
        assertThat(sig1.headerValue()).isEqualTo(sig2.headerValue());
    }

    @Test
    @DisplayName("Different payloads produce different signature digests")
    void generateSignature_DifferentPayloads_ProduceDifferentDigests() {
        Instant timestamp = Instant.ofEpochSecond(1773000000L);
        WebhookSignature sig1 = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, timestamp);
        WebhookSignature sig2 = signer.generateSignature("{\"different\":\"payload\"}", TEST_SECRET, timestamp);

        assertThat(sig1.hexDigest()).isNotEqualTo(sig2.hexDigest());
    }

    @Test
    @DisplayName("Different secrets produce different signature digests")
    void generateSignature_DifferentSecrets_ProduceDifferentDigests() {
        Instant timestamp = Instant.ofEpochSecond(1773000000L);
        String altSecret = "swd_whsec_fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
        WebhookSignature sig1 = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, timestamp);
        WebhookSignature sig2 = signer.generateSignature(TEST_PAYLOAD, altSecret, timestamp);

        assertThat(sig1.hexDigest()).isNotEqualTo(sig2.hexDigest());
    }

    @Test
    @DisplayName("Different timestamps produce different signature digests (replay protection)")
    void generateSignature_DifferentTimestamps_ProduceDifferentDigests() {
        WebhookSignature sig1 = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, Instant.ofEpochSecond(1773000000L));
        WebhookSignature sig2 = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, Instant.ofEpochSecond(1773000001L));

        assertThat(sig1.hexDigest()).isNotEqualTo(sig2.hexDigest());
    }

    @Test
    @DisplayName("Valid signature passes verification within 300-second window")
    void verifySignature_ValidSignature_Passes() {
        Instant now = Instant.now();
        WebhookSignature signature = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, now);

        boolean valid = signer.verifySignature(TEST_PAYLOAD, signature.headerValue(), TEST_SECRET, 300L, now);
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("Tampered payload fails signature verification")
    void verifySignature_TamperedPayload_Fails() {
        Instant now = Instant.now();
        WebhookSignature signature = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, now);

        String tamperedPayload = TEST_PAYLOAD.replace("parcel.status.changed.v1", "parcel.status.changed.v2");
        boolean valid = signer.verifySignature(tamperedPayload, signature.headerValue(), TEST_SECRET, 300L, now);
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("Wrong secret fails signature verification")
    void verifySignature_WrongSecret_Fails() {
        Instant now = Instant.now();
        WebhookSignature signature = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, now);

        String wrongSecret = "swd_whsec_ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        boolean valid = signer.verifySignature(TEST_PAYLOAD, signature.headerValue(), wrongSecret, 300L, now);
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("Stale signature (> 300 seconds old) fails replay protection check")
    void verifySignature_StaleTimestamp_FailsReplayProtection() {
        Instant past = Instant.now().minusSeconds(301);
        WebhookSignature signature = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, past);

        boolean valid = signer.verifySignature(TEST_PAYLOAD, signature.headerValue(), TEST_SECRET, 300L, Instant.now());
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("Future timestamp (> 300 seconds ahead) fails replay protection check")
    void verifySignature_FutureTimestamp_FailsReplayProtection() {
        Instant future = Instant.now().plusSeconds(301);
        WebhookSignature signature = signer.generateSignature(TEST_PAYLOAD, TEST_SECRET, future);

        boolean valid = signer.verifySignature(TEST_PAYLOAD, signature.headerValue(), TEST_SECRET, 300L, Instant.now());
        assertThat(valid).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "invalid_header_format",
            "t=123",
            "v1=abc",
            "t=abc,v1=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "t=1773000000,v1=short_sig",
            ""
    })
    @DisplayName("Malformed header formats fail verification safely without exceptions")
    void verifySignature_MalformedHeader_ReturnsFalse(String malformedHeader) {
        boolean valid = signer.verifySignature(TEST_PAYLOAD, malformedHeader, TEST_SECRET, 300L, Instant.now());
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("Null or blank payload/secret throws IllegalArgumentException on generation")
    void generateSignature_InvalidInputs_Throws() {
        assertThatThrownBy(() -> signer.generateSignature(null, TEST_SECRET))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> signer.generateSignature(TEST_PAYLOAD, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> signer.generateSignature(TEST_PAYLOAD, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
