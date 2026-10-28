package com.swiftdrop.communicationsservice.consumer;

/**
 * Thrown when an authorized recipient reference cannot be found in Accounts Service.
 */
public class RecipientNotFoundException extends RuntimeException {

    public RecipientNotFoundException(String message) {
        super(message);
    }
}
