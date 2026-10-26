package com.swiftdrop.deliveryservice.proof.exception;

public class ProofValidationException extends RuntimeException {

    public ProofValidationException(String message) {
        super(message);
    }

    public ProofValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
