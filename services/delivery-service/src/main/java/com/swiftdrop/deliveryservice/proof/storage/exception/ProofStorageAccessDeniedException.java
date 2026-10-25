package com.swiftdrop.deliveryservice.proof.storage.exception;

/** Raised when the configured backend credential cannot perform the requested storage operation. */
public final class ProofStorageAccessDeniedException extends ProofStorageException {

    public ProofStorageAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
