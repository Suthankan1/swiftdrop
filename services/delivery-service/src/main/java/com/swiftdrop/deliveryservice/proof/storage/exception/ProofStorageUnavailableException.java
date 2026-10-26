package com.swiftdrop.deliveryservice.proof.storage.exception;

/** Raised when the private object store cannot complete an operation. */
public final class ProofStorageUnavailableException extends ProofStorageException {

    public ProofStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
