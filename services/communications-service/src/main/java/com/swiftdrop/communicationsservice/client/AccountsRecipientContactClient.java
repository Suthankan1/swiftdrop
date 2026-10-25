package com.swiftdrop.communicationsservice.client;

import com.swiftdrop.communicationsservice.notification.NotificationChannel;

import java.util.Optional;

/**
 * Client interface for resolving permitted notification contacts from Accounts Service (IF-GRPC-03, #110).
 * Enforces server-side channel scoping, fail-closed eligibility, and privacy-preserving redaction.
 */
public interface AccountsRecipientContactClient {

    /**
     * Resolves permitted contact details for an approved recipient reference and notification channel.
     *
     * @param recipientSub Keycloak recipient subject reference (non-blank)
     * @param purpose notification purpose/scope (e.g. "PARCEL_STATUS_UPDATE")
     * @param channel notification channel (e.g. EMAIL or SMS_MOCK)
     * @return PermittedRecipientContact if found, or empty if unknown recipient
     * @throws AccountsServiceUnavailableException if Accounts is unreachable or deadline exceeded
     * @throws AccountsSecurityException if caller authorization fails
     * @throws IllegalArgumentException if recipientSub, purpose, or channel is invalid
     */
    Optional<PermittedRecipientContact> resolveContact(String recipientSub, String purpose, NotificationChannel channel);
}
