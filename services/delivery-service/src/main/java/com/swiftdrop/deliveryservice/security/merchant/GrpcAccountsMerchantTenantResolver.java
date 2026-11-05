package com.swiftdrop.deliveryservice.security.merchant;

import com.swiftdrop.accounts.v1.AccountEligibilityRequest;
import com.swiftdrop.accounts.v1.AccountEligibilityResponse;
import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * gRPC adapter resolving merchant tenant identity against Accounts & Partners service (IF-GRPC-01).
 * Enforces server-side approved lifecycle status and fails closed.
 */
public class GrpcAccountsMerchantTenantResolver implements MerchantTenantResolver {

    private static final Logger log = LoggerFactory.getLogger(GrpcAccountsMerchantTenantResolver.class);
    public static final long DEFAULT_DEADLINE_MS = 2000L;

    private final AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub;
    private final long deadlineMs;

    public GrpcAccountsMerchantTenantResolver(AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub) {
        this(stub, DEFAULT_DEADLINE_MS);
    }

    public GrpcAccountsMerchantTenantResolver(AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub, long deadlineMs) {
        this.stub = stub;
        this.deadlineMs = deadlineMs > 0 ? deadlineMs : DEFAULT_DEADLINE_MS;
    }

    @Override
    public Optional<UUID> resolveMerchantTenant(String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }

        AccountEligibilityRequest request = AccountEligibilityRequest.newBuilder()
                .setSubject(subject.trim())
                .setRequiredRole("MERCHANT")
                .build();

        try {
            AccountEligibilityResponse response = stub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .checkAccountEligibility(request);

            if (!response.getIsEligible() || !response.getIsActive()) {
                log.warn("Merchant tenant resolution rejected by Accounts [subject={}, reason={}, status={}]",
                        subject, response.getRejectionReason(), response.getStatus());
                return Optional.empty();
            }

            String merchantIdStr = response.getMerchantId();
            if (merchantIdStr == null || merchantIdStr.isBlank()) {
                log.warn("Accounts returned empty merchant_id for subject [subject={}]", subject);
                return Optional.empty();
            }

            return Optional.of(UUID.fromString(merchantIdStr.trim()));
        } catch (StatusRuntimeException ex) {
            Status.Code code = ex.getStatus().getCode();
            log.warn("Accounts checkAccountEligibility gRPC failed [subject={}, code={}, desc={}]",
                    subject, code, ex.getStatus().getDescription());

            if (code == Status.Code.DEADLINE_EXCEEDED || code == Status.Code.UNAVAILABLE) {
                throw new AccountsUnavailableException("Accounts service unavailable during merchant tenant resolution: " + code, ex);
            }
            return Optional.empty();
        } catch (IllegalArgumentException ex) {
            log.error("Malformed merchantId UUID returned by Accounts [subject={}]", subject, ex);
            return Optional.empty();
        }
    }
}
