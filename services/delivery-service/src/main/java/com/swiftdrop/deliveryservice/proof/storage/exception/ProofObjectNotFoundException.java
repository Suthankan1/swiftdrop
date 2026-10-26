package com.swiftdrop.deliveryservice.proof.storage.exception;

/** Raised when a validated application-owned reference has no backing object. */
public final class ProofObjectNotFoundException extends ProofStorageException {

    public ProofObjectNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
