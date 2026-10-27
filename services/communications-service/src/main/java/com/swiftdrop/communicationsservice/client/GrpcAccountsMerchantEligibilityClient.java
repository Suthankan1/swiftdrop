package com.swiftdrop.communicationsservice.client;

import com.swiftdrop.accounts.v1.AccountEligibilityRequest;
import com.swiftdrop.accounts.v1.AccountEligibilityResponse;
import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.communicationsservice.webhook.exception.MerchantNotApprovedException;
import com.swiftdrop.communicationsservice.webhook.exception.MerchantNotFoundException;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Production gRPC adapter implementing AccountsMerchantEligibilityClient (IF-GRPC-01, #109, #116).
 * Queries AccountsInternalService.CheckAccountEligibility using authenticated machine credentials.
 * Enforces server-side subject resolution, bounded deadlines, and fail-closed tenant validation.
 */
public class GrpcAccountsMerchantEligibilityClient implements AccountsMerchantEligibilityClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcAccountsMerchantEligibilityClient.class);
    public static final long DEFAULT_DEADLINE_MS = 2000L;

    private final AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub blockingStub;
    private final long deadlineMs;

    public GrpcAccountsMerchantEligibilityClient(Channel channel, long deadlineMs) {
        Objects.requireNonNull(channel, "gRPC channel is required");
        this.blockingStub = AccountsInternalServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs > 0 ? deadlineMs : DEFAULT_DEADLINE_MS;
    }

    public GrpcAccountsMerchantEligibilityClient(AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub, long deadlineMs) {
        this.blockingStub = Objects.requireNonNull(stub, "blockingStub is required");
        this.deadlineMs = deadlineMs > 0 ? deadlineMs : DEFAULT_DEADLINE_MS;
    }

    public GrpcAccountsMerchantEligibilityClient(Channel channel) {
        this(channel, DEFAULT_DEADLINE_MS);
    }

    @Override
    public ApprovedMerchantTenant resolveApprovedMerchant(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Subject reference is required.");
        }

        String sanitizedSub = subject.trim();
        log.debug("Verifying merchant eligibility in Accounts [subject={}]", sanitizedSub);

        AccountEligibilityRequest request = AccountEligibilityRequest.newBuilder()
                .setSubject(sanitizedSub)
                .setRequiredRole("MERCHANT")
                .build();

        AccountEligibilityResponse response;
        try {
            response = blockingStub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .checkAccountEligibility(request);
        } catch (StatusRuntimeException ex) {
            Status.Code code = ex.getStatus().getCode();
            String description = ex.getStatus().getDescription();

            if (code == Status.Code.NOT_FOUND) {
                log.warn("Merchant principal not found in Accounts [subject={}]", sanitizedSub);
                throw new MerchantNotFoundException("Merchant tenant not found for authenticated principal.");
            }

            if (code == Status.Code.DEADLINE_EXCEEDED || code == Status.Code.UNAVAILABLE) {
                log.error("Accounts service unavailable or timed out during merchant eligibility check [subject={}, code={}]",
                        sanitizedSub, code);
                throw new AccountsServiceUnavailableException(
                        "Accounts service eligibility check unavailable (" + code + "): " + description, ex);
            }

            if (code == Status.Code.PERMISSION_DENIED || code == Status.Code.UNAUTHENTICATED) {
                log.error("Service authorization rejected by Accounts during merchant eligibility check [subject={}, code={}]",
                        sanitizedSub, code);
                throw new AccountsSecurityException(
                        "Accounts merchant check authorization denied (" + code + "): " + description, ex);
            }

            if (code == Status.Code.INVALID_ARGUMENT) {
                log.warn("Invalid eligibility request to Accounts [subject={}, desc={}]", sanitizedSub, description);
                throw new IllegalArgumentException("Invalid merchant check request: " + description, ex);
            }

            log.error("Unexpected gRPC error during merchant eligibility check [subject={}, code={}]", sanitizedSub, code);
            throw new AccountsContactException("Failed to check merchant eligibility in Accounts: " + description, ex);
        }

        String status = response.getStatus();
        if ("NOT_FOUND".equalsIgnoreCase(status) || response.getMerchantId() == null || response.getMerchantId().isBlank()) {
            log.warn("Merchant tenant not found for authenticated subject [subject={}]", sanitizedSub);
            throw new MerchantNotFoundException("Merchant tenant not found for authenticated principal.");
        }

        UUID merchantId;
        try {
            merchantId = UUID.fromString(response.getMerchantId().trim());
        } catch (IllegalArgumentException ex) {
            log.error("Accounts returned invalid merchantId UUID format [subject={}, merchantId={}]",
                    sanitizedSub, response.getMerchantId());
            throw new IllegalStateException("Accounts returned invalid merchant UUID format: " + response.getMerchantId(), ex);
        }

        boolean isApproved = "APPROVED".equalsIgnoreCase(status) && response.getIsEligible() && response.getIsActive();
        if (!isApproved) {
            log.warn("Merchant is not approved for webhook configuration [subject={}, merchantId={}, status={}, eligible={}, active={}, reason={}]",
                    sanitizedSub, merchantId, status, response.getIsEligible(), response.getIsActive(), response.getRejectionReason());
            throw new MerchantNotApprovedException(
                    "Merchant account is not authorized or approval is pending (status: " + status + ").");
        }

        log.debug("Merchant successfully resolved and verified [subject={}, merchantId={}, businessName={}]",
                sanitizedSub, merchantId, response.getBusinessName());

        return new ApprovedMerchantTenant(
                merchantId,
                response.getSubject(),
                response.getBusinessName(),
                status,
                response.getIsEligible(),
                response.getIsActive()
        );
    }
}
