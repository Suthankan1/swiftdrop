package com.swiftdrop.deliveryservice.proof.storage.exception;

/** Raised when the private object-store operation exceeds its configured deadline. */
public final class ProofStorageTimeoutException extends ProofStorageException {

    public ProofStorageTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
