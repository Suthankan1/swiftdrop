package com.swiftdrop.deliveryservice.idempotency;

import java.util.Optional;

/**
 * Service contract for managing idempotent API operations in Delivery Operations.
 */
public interface IdempotencyServiceContract {

    /**
     * Looks up an existing idempotency record by caller principal and key.
     */
    Optional<IdempotencyRecord> findRecord(String principalOrTenant, String idempotencyKey);

    /**
     * Attempts to acquire an in-progress idempotency lease for the operation.
     */
    IdempotencyRecord acquireLease(String principalOrTenant, String idempotencyKey, String requestDigest);

    /**
     * Marks an idempotency record as successfully completed with its stored result payload.
     */
    void completeRecord(String principalOrTenant, String idempotencyKey, String storedResult);

    /**
     * Marks an idempotency record as failed so retry may proceed if permitted.
     */
    void failRecord(String principalOrTenant, String idempotencyKey);
}
