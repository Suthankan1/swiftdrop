package com.swiftdrop.accountsservice.merchant.credential;

import com.swiftdrop.accountsservice.merchant.MerchantStatus;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record MerchantKeyValidationResult(
        boolean isValid,
        UUID merchantId,
        String businessName,
        MerchantStatus status,
        List<String> scopes,
        String rejectionReason
) {
    public static MerchantKeyValidationResult valid(UUID merchantId, String businessName, MerchantStatus status, List<String> scopes) {
        return new MerchantKeyValidationResult(true, merchantId, businessName, status, scopes, null);
    }

    public static MerchantKeyValidationResult invalid(String rejectionReason) {
        return new MerchantKeyValidationResult(false, null, null, null, Collections.emptyList(), rejectionReason);
    }

    public static MerchantKeyValidationResult unapprovedMerchant(UUID merchantId, String businessName, MerchantStatus status, List<String> scopes, String rejectionReason) {
        return new MerchantKeyValidationResult(false, merchantId, businessName, status, scopes, rejectionReason);
    }
}
