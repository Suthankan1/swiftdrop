package com.swiftdrop.communicationsservice.client;

import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.accounts.v1.RecipientContactRequest;
import com.swiftdrop.accounts.v1.RecipientContactResponse;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Production gRPC adapter implementing AccountsRecipientContactClient (IF-GRPC-03, #110).
 * Queries AccountsInternalService.ResolveRecipientContact using authenticated machine credentials.
 * Enforces server-side channel scoping, bounded deadlines, and zero contact leakage in logs.
 */
public class GrpcAccountsRecipientContactClient implements AccountsRecipientContactClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcAccountsRecipientContactClient.class);
    public static final long DEFAULT_DEADLINE_MS = 2000L;

    private final AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub blockingStub;
    private final long deadlineMs;

    public GrpcAccountsRecipientContactClient(Channel channel, long deadlineMs) {
        Objects.requireNonNull(channel, "gRPC channel is required");
        this.blockingStub = AccountsInternalServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs > 0 ? deadlineMs : DEFAULT_DEADLINE_MS;
    }

    public GrpcAccountsRecipientContactClient(AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub, long deadlineMs) {
        this.blockingStub = Objects.requireNonNull(stub, "blockingStub is required");
        this.deadlineMs = deadlineMs > 0 ? deadlineMs : DEFAULT_DEADLINE_MS;
    }

    public GrpcAccountsRecipientContactClient(Channel channel) {
        this(channel, DEFAULT_DEADLINE_MS);
    }

    @Override
    public Optional<PermittedRecipientContact> resolveContact(String recipientSub, String purpose, NotificationChannel channel) {
        if (recipientSub == null || recipientSub.isBlank()) {
            throw new IllegalArgumentException("recipientSub is required");
        }

        String sanitizedSub = recipientSub.trim();
        String channelStr = channel != null ? channel.name() : "ANY";
        String purposeStr = (purpose != null && !purpose.isBlank()) ? purpose.trim() : "PARCEL_STATUS_UPDATE";

        log.debug("Resolving permitted notification contact [recipientSub={}, channel={}, purpose={}]",
                sanitizedSub, channelStr, purposeStr);

        RecipientContactRequest request = RecipientContactRequest.newBuilder()
                .setRecipientSub(sanitizedSub)
                .setPurpose(purposeStr)
                .setChannel(channelStr)
                .build();

        try {
            RecipientContactResponse response = blockingStub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .resolveRecipientContact(request);

            log.info("Resolved notification contact policy [recipientSub={}, eligible={}, status={}, reason={}]",
                    sanitizedSub, response.getIsEligible(), response.getStatus(), response.getRejectionReason());

            PermittedRecipientContact contact = new PermittedRecipientContact(
                    response.getRecipientSub(),
                    response.getEmail(),
                    response.getPhoneNumber(),
                    response.getFullName(),
                    response.getIsEligible(),
                    response.getEmailPermitted(),
                    response.getSmsPermitted(),
                    response.getStatus(),
                    response.getRejectionReason()
            );

            return Optional.of(contact);

        } catch (StatusRuntimeException ex) {
            Status.Code code = ex.getStatus().getCode();
            String description = ex.getStatus().getDescription();

            if (code == Status.Code.NOT_FOUND) {
                log.warn("Recipient reference not found in Accounts [recipientSub={}]", sanitizedSub);
                return Optional.empty();
            }

            if (code == Status.Code.DEADLINE_EXCEEDED || code == Status.Code.UNAVAILABLE) {
                log.error("Accounts service unavailable or timed out during contact resolution [recipientSub={}, code={}]",
                        sanitizedSub, code);
                throw new AccountsServiceUnavailableException(
                        "Accounts service contact resolution unavailable (" + code + "): " + description, ex);
            }

            if (code == Status.Code.PERMISSION_DENIED || code == Status.Code.UNAUTHENTICATED) {
                log.error("Machine caller authorization rejected by Accounts [recipientSub={}, code={}]",
                        sanitizedSub, code);
                throw new AccountsSecurityException(
                        "Accounts contact resolution denied (" + code + "): " + description, ex);
            }

            if (code == Status.Code.INVALID_ARGUMENT) {
                log.warn("Invalid contact lookup request [recipientSub={}, desc={}]", sanitizedSub, description);
                throw new IllegalArgumentException("Invalid contact resolution request: " + description, ex);
            }

            log.error("Unexpected gRPC error resolving contact [recipientSub={}, code={}]", sanitizedSub, code);
            throw new AccountsContactException("Failed to resolve contact from Accounts: " + description, ex);
        }
    }
}
