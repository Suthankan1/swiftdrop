package com.swiftdrop.graphql.resolver;

import java.util.List;

/**
 * Authoritative Merchant dashboard record matching GraphQL SDL MerchantDashboard type.
 * merchantId and businessName are sourced from the trusted Accounts eligibility response,
 * never from caller-supplied arguments.
 */
public record MerchantDashboard(
        String merchantId,
        String businessName,
        String status,
        int totalOrdersCount,
        int activeOrdersCount,
        int deliveredOrdersCount,
        int failedOrdersCount,
        List<MerchantOrderSummaryItem> recentOrders
) {
    public MerchantDashboard {
        recentOrders = recentOrders == null ? List.of() : List.copyOf(recentOrders);
    }
}
