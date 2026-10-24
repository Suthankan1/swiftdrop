package com.swiftdrop.accountsservice.merchant.credential;

public class ApiKeyLimitExceededException extends RuntimeException {
    public ApiKeyLimitExceededException(String message) {
        super(message);
    }
}
