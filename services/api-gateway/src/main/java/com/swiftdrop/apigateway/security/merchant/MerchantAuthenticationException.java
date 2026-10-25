package com.swiftdrop.apigateway.security.merchant;

import org.springframework.security.core.AuthenticationException;

/**
 * Authentication exception thrown during merchant machine API-key validation.
 * When isAccessDenied is true, indicates that the credential was recognized but the merchant
 * organization is not in APPROVED status or lacks required scopes (maps to HTTP 403 Forbidden).
 * Otherwise, indicates an unauthenticated credential (maps to HTTP 401 Unauthorized).
 */
public class MerchantAuthenticationException extends AuthenticationException {

    private final boolean accessDenied;
    private final String merchantId;
    private final String reasonCode;

    public MerchantAuthenticationException(String message) {
        this(message, false, null, "UNAUTHENTICATED");
    }

    public MerchantAuthenticationException(String message, boolean accessDenied, String merchantId, String reasonCode) {
        super(message);
        this.accessDenied = accessDenied;
        this.merchantId = merchantId;
        this.reasonCode = reasonCode;
    }

    public boolean isAccessDenied() {
        return accessDenied;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
