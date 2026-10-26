package com.swiftdrop.deliveryservice.proof.storage;

/**
 * Application-facing port for private proof bytes.
 *
 * <p>This port intentionally has no HTTP, authorization, proof-metadata, retention, replacement,
 * or evidence-policy responsibilities.</p>
 */
public interface ProofObjectStorage {

    StoredProofObject store(ProofObjectUpload upload);

    RetrievedProofObject retrieve(ProofObjectReference reference);
}
