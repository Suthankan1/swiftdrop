package com.swiftdrop.deliveryservice.proof.storage;

/** Result of a completed private object-store write. */
public record StoredProofObject(
        ProofObjectReference reference,
        long contentLength,
        String contentType
) {
}
