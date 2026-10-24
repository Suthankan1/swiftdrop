package com.swiftdrop.accountsservice.merchant.credential;

/**
 * In-process provider interface for validating merchant API key digests and business approval state.
 * Implements the Accounts-owned verification boundary defined in DEC-MER-03 and SERVICE_INTERFACE_MATRIX IF-GRPC-02.
 */
public interface MerchantApiKeyValidator {

    MerchantKeyValidationResult validateKeyDigest(String keyPrefix, String keyDigest);
}
