package com.swiftdrop.communicationsservice.client;

/**
 * Non-retryable security exception indicating that the calling service identity was rejected
 * or actor delegation spoofing was detected by Delivery Operations.
 */
public class DeliverySecurityException extends DeliveryAuthorizationException {
    public DeliverySecurityException(String message) {
        super(message);
    }

    public DeliverySecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
