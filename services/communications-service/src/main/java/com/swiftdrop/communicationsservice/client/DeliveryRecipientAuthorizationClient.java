package com.swiftdrop.communicationsservice.client;

import java.util.UUID;

/**
 * Client interface for invoking Delivery event-to-recipient authorization read (IF-GRPC-11, #111).
 * Resolves Delivery-owned state to verify event provenance, parcel pairing, recipient binding,
 * and cross-tenant isolation before scheduling durable notification jobs.
 */
public interface DeliveryRecipientAuthorizationClient {

    /**
     * Authorizes a delivery lifecycle event against an optional candidate recipient and candidate merchant.
     *
     * @param eventId UUID of the domain lifecycle event
     * @param parcelId UUID of the parcel aggregate root
     * @param candidateRecipientSub optional candidate Keycloak subject string to verify, or null to resolve primary
     * @param candidateMerchantId optional merchant tenant UUID to verify cross-tenant boundaries, or null
     * @param allowStale whether stale events (event_version < current_version) should be considered authorized
     * @return DeliveryEventAuthorization outcome with minimal references and zero contact PII
     * @throws DeliveryServiceUnavailableException if Delivery is unreachable or timed out (retryable)
     * @throws DeliverySecurityException if machine authentication or delegation policy is rejected
     * @throws IllegalArgumentException if eventId or parcelId is null
     */
    DeliveryEventAuthorization authorizeEventRecipient(
            UUID eventId,
            UUID parcelId,
            String candidateRecipientSub,
            UUID candidateMerchantId,
            boolean allowStale
    );

    /**
     * Convenience method to authorize a delivery lifecycle event with default parameters.
     */
    default DeliveryEventAuthorization authorizeEventRecipient(UUID eventId, UUID parcelId) {
        return authorizeEventRecipient(eventId, parcelId, null, null, false);
    }
}
