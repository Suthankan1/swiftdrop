package com.swiftdrop.deliveryservice.proof.storage.exception;

/** Raised before storage access when proof content or a reference is malformed. */
public final class InvalidProofObjectException extends ProofStorageException {

    public InvalidProofObjectException(String message) {
        super(message);
    }

    public InvalidProofObjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
