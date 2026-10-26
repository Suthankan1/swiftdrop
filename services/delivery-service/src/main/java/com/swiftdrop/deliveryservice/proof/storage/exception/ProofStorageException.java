package com.swiftdrop.deliveryservice.proof.storage.exception;

/** Base type for sanitized private-storage failures. */
public class ProofStorageException extends RuntimeException {

    public ProofStorageException(String message) {
        super(message);
    }

    public ProofStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
