package com.swiftdrop.graphql.resolver.admin;

/**
 * Authoritative Pending Merchant review preview item matching GraphQL SDL PendingMerchantItem type.
 */
public record PendingMerchantItem(
        String merchantId,
        String businessName,
        String registrationNumber,
        String submittedAt
) {}
