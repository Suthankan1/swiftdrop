package com.swiftdrop.deliveryservice.proof;

import java.util.UUID;

/**
 * Bounded feature contract for Proof-of-Delivery metadata.
 *
 * Owned and implemented under Issue #29 (Courier Proof Capture & Delivery Metadata).
 */
public interface ProofMetadataContract {

    /**
     * Validates that the supplied proof reference exists in private object storage metadata.
     */
    boolean isProofValid(UUID parcelId, UUID proofId);
}
