package com.swiftdrop.pricing.error;

/** Admin-specific status semantics leave the existing quote API errors unchanged. */
public class AdminConfigurationException extends PricingDomainException {
    private final int status;

    public AdminConfigurationException(int status, String code, String detail) {
        super(code, detail);
        this.status = status;
    }

    public int getStatus() { return status; }
}
