package com.swiftdrop.accountsservice.merchant.credential;

public class InvalidApiKeyRequestException extends RuntimeException {
    public InvalidApiKeyRequestException(String message) {
        super(message);
    }
}
