package com.swiftdrop.communicationsservice.webhook.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standard implementation of WebhookSigner using HMAC-SHA256.
 * Enforces:
 * 1. Payload and timestamp canonical concatenation: t + "." + payload_json.
 * 2. Lowercase hex formatting for signature digest.
 * 3. Constant-time equality comparison during verification (MessageDigest.isEqual).
 * 4. Replay attack protection window (default 300 seconds).
 * 5. Safe handling without logging or leaking secret material.
 */
@Component
public class DefaultWebhookSigner implements WebhookSigner {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebhookSigner.class);

    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "^t=(?<timestamp>\\d+),\\s*v1=(?<sig>[0-9a-fA-F]{64})$"
    );

    @Override
    public WebhookSignature generateSignature(String payloadJson, String plaintextSecret) {
        return generateSignature(payloadJson, plaintextSecret, Instant.now());
    }

    @Override
    public WebhookSignature generateSignature(String payloadJson, String plaintextSecret, Instant timestamp) {
        if (payloadJson == null) {
            throw new IllegalArgumentException("payloadJson cannot be null");
        }
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            throw new IllegalArgumentException("plaintextSecret cannot be null or blank");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }

        long epochSeconds = timestamp.getEpochSecond();
        String dataToSign = epochSeconds + "." + payloadJson;

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    plaintextSecret.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            mac.init(keySpec);
            byte[] digest = mac.doFinal(dataToSign.getBytes(StandardCharsets.UTF_8));
            String hexDigest = HexFormat.of().formatHex(digest);
            String headerValue = "t=" + epochSeconds + ",v1=" + hexDigest;

            return new WebhookSignature(epochSeconds, hexDigest, headerValue);

        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            log.error("Failed to compute HMAC-SHA256 webhook signature", ex);
            throw new IllegalStateException("Cryptographic HMAC-SHA256 signing failure", ex);
        }
    }

    @Override
    public boolean verifySignature(String payloadJson, String signatureHeader, String plaintextSecret) {
        return verifySignature(payloadJson, signatureHeader, plaintextSecret,
                DEFAULT_REPLAY_TOLERANCE_SECONDS, Instant.now());
    }

    @Override
    public boolean verifySignature(String payloadJson, String signatureHeader, String plaintextSecret,
                                   long toleranceSeconds, Instant currentTime) {
        if (payloadJson == null || signatureHeader == null || plaintextSecret == null || plaintextSecret.isBlank()) {
            return false;
        }

        Matcher matcher = HEADER_PATTERN.matcher(signatureHeader.trim());
        if (!matcher.matches()) {
            log.debug("Webhook signature header failed regex structure check: {}", signatureHeader);
            return false;
        }

        long headerTimestamp;
        try {
            headerTimestamp = Long.parseLong(matcher.group("timestamp"));
        } catch (NumberFormatException ex) {
            return false;
        }

        String providedHex = matcher.group("sig").toLowerCase();

        // 1. Replay attack window check
        if (toleranceSeconds > 0) {
            Instant now = currentTime != null ? currentTime : Instant.now();
            long diff = Math.abs(now.getEpochSecond() - headerTimestamp);
            if (diff > toleranceSeconds) {
                log.warn("Webhook signature rejected: timestamp skew exceeds tolerance [diff={}s, max={}s]",
                        diff, toleranceSeconds);
                return false;
            }
        }

        // 2. Compute expected HMAC
        String dataToSign = headerTimestamp + "." + payloadJson;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    plaintextSecret.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            mac.init(keySpec);
            byte[] expectedDigest = mac.doFinal(dataToSign.getBytes(StandardCharsets.UTF_8));
            String expectedHex = HexFormat.of().formatHex(expectedDigest);

            // 3. Constant-time comparison
            return MessageDigest.isEqual(
                    expectedHex.getBytes(StandardCharsets.UTF_8),
                    providedHex.getBytes(StandardCharsets.UTF_8)
            );

        } catch (Exception ex) {
            log.error("Error during webhook signature verification", ex);
            return false;
        }
    }
}
