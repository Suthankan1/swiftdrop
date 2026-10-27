package com.swiftdrop.deliveryservice.security.merchant;

import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.accounts.v1.MerchantKeyDigestRequest;
import com.swiftdrop.accounts.v1.MerchantKeyDigestResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Authoritative gRPC adapter validating merchant API key digests against Accounts & Partners (IF-GRPC-02).
 * Operates strictly with constant-time equality in Accounts and never exposes key material downstream.
 */
public class GrpcAccountsMerchantKeyValidator implements AccountsMerchantKeyValidator {

    private static final Logger log = LoggerFactory.getLogger(GrpcAccountsMerchantKeyValidator.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^swd_live_[0-9a-zA-Z]{32}$");
    public static final long DEFAULT_DEADLINE_MS = 2000L;

    private final AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub;
    private final long deadlineMs;

    public GrpcAccountsMerchantKeyValidator(AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub) {
        this(stub, DEFAULT_DEADLINE_MS);
    }

    public GrpcAccountsMerchantKeyValidator(AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub, long deadlineMs) {
        this.stub = stub;
        this.deadlineMs = deadlineMs > 0 ? deadlineMs : DEFAULT_DEADLINE_MS;
    }

    @Override
    public MerchantKeyValidationResult validateApiKey(String rawApiKey) {
        if (rawApiKey == null || !KEY_PATTERN.matcher(rawApiKey).matches()) {
            return MerchantKeyValidationResult.invalid("MALFORMED_KEY");
        }

        String keyPrefix = rawApiKey.substring(0, 12);
        String keyDigest = sha256Hex(rawApiKey);

        MerchantKeyDigestRequest request = MerchantKeyDigestRequest.newBuilder()
                .setKeyPrefix(keyPrefix)
                .setKeyDigest(keyDigest)
                .build();

        try {
            MerchantKeyDigestResponse response = stub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .validateMerchantKeyDigest(request);

            if (!response.getIsValid()) {
                log.warn("Merchant API key digest validation rejected by Accounts [prefix={}]", keyPrefix);
                return MerchantKeyValidationResult.invalid("INVALID_KEY");
            }

            UUID merchantId = null;
            if (response.getMerchantId() != null && !response.getMerchantId().isBlank()) {
                try {
                    merchantId = UUID.fromString(response.getMerchantId().trim());
                } catch (IllegalArgumentException e) {
                    log.error("Accounts returned invalid merchantId UUID format [prefix={}]", keyPrefix);
                    return MerchantKeyValidationResult.invalid("ACCOUNTS_MALFORMED_RESPONSE");
                }
            }

            return MerchantKeyValidationResult.valid(
                    merchantId,
                    response.getBusinessName(),
                    response.getStatus(),
                    Set.copyOf(response.getScopesList())
            );

        } catch (StatusRuntimeException ex) {
            Status.Code code = ex.getStatus().getCode();
            log.warn("Accounts validateMerchantKeyDigest gRPC failed [prefix={}, code={}, desc={}]",
                    keyPrefix, code, ex.getStatus().getDescription());

            if (code == Status.Code.DEADLINE_EXCEEDED || code == Status.Code.UNAVAILABLE) {
                throw new AccountsUnavailableException("Accounts service unavailable: " + code, ex);
            }
            return MerchantKeyValidationResult.invalid("ACCOUNTS_ERROR");
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
