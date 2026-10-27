package com.swiftdrop.communicationsservice.webhook.delivery;

import java.time.Instant;

/**
 * Contract for HMAC-SHA256 payload signing and receiver-side verification.
 * Adheres to DEC-COM-02, IF-WHK-01, and Section 6.4 of NOV_11_COMMUNICATIONS_MERCHANT_REVIEW.
 *
 * Header: X-SwiftDrop-Signature: t={timestamp},v1={hex_digest}
 * Signed data: t + "." + payload_json
 */
public interface WebhookSigner {

    String SIGNATURE_HEADER_NAME = "X-SwiftDrop-Signature";
    String HMAC_ALGORITHM = "HmacSHA256";
    long DEFAULT_REPLAY_TOLERANCE_SECONDS = 300L; // 5 minutes

    /**
     * Computes HMAC-SHA256 signature using current epoch timestamp.
     *
     * @param payloadJson UTF-8 JSON payload to sign
     * @param plaintextSecret decrypted merchant signing secret (swd_whsec_...)
     * @return WebhookSignature containing timestamp, hex digest, and header value
     */
    WebhookSignature generateSignature(String payloadJson, String plaintextSecret);

    /**
     * Computes HMAC-SHA256 signature using an explicit timestamp.
     *
     * @param payloadJson UTF-8 JSON payload to sign
     * @param plaintextSecret decrypted merchant signing secret
     * @param timestamp explicit signing timestamp
     * @return WebhookSignature
     */
    WebhookSignature generateSignature(String payloadJson, String plaintextSecret, Instant timestamp);

    /**
     * Verifies signature header using default 300-second replay tolerance window.
     *
     * @param payloadJson raw transmitted JSON payload
     * @param signatureHeader value of X-SwiftDrop-Signature header (t=...,v1=...)
     * @param plaintextSecret merchant signing secret
     * @return true if signature is valid and within replay window
     */
    boolean verifySignature(String payloadJson, String signatureHeader, String plaintextSecret);

    /**
     * Verifies signature header against explicit tolerance window and reference time.
     *
     * @param payloadJson raw transmitted JSON payload
     * @param signatureHeader value of X-SwiftDrop-Signature header
     * @param plaintextSecret merchant signing secret
     * @param toleranceSeconds maximum allowed clock skew in seconds (<= 0 disables check)
     * @param currentTime reference time for replay check
     * @return true if valid
     */
    boolean verifySignature(String payloadJson, String signatureHeader, String plaintextSecret,
                            long toleranceSeconds, Instant currentTime);
}
