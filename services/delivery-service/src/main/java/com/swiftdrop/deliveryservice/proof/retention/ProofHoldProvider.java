package com.swiftdrop.deliveryservice.proof.retention;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for querying authoritative legal and operational holds on delivery proof and parcels.
 *
 * <p>Fail-closed contract: if hold status cannot be authoritatively determined,
 * implementations must return {@code Optional.empty()}, which strictly halts retention cleanup.</p>
 */
public interface ProofHoldProvider {

    /**
     * Determines whether the given proof or parcel is currently under an active legal or operational hold.
     *
     * @param parcelId authoritative parcel identifier
     * @param proofId  authoritative proof identifier
     * @return {@code Optional.of(true)} if held,
     *         {@code Optional.of(false)} if explicitly verified clear,
     *         {@code Optional.empty()} if hold status cannot be authoritatively determined (fails closed)
     */
    Optional<Boolean> isUnderHold(UUID parcelId, UUID proofId);
}
