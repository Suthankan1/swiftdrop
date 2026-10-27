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

    default void delete(ProofObjectReference reference) {
        // default no-op for storage implementations that do not support deletion
    }

    /**
     * Indicates whether this storage implementation supports guaranteed physical deletion.
     * Default is false to prevent treating no-op deletion as successful retention cleanup.
     */
    default boolean supportsDeletion() {
        return false;
    }
}
