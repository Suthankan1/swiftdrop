package com.swiftdrop.accountsservice.merchant.credential;

public class ApiKeyNotFoundException extends RuntimeException {
    public ApiKeyNotFoundException(String message) {
        super(message);
    }
}
