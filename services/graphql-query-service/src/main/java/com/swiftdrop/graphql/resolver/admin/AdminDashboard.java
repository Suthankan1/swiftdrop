package com.swiftdrop.graphql.resolver.admin;

import java.util.List;

/**
 * Authoritative Admin operational and onboarding dashboard record matching GraphQL SDL AdminDashboard type.
 */
public record AdminDashboard(
        int pendingMerchantsCount,
        int totalParcelsCount,
        int activeParcelsCount,
        double terminalSuccessRate,
        List<PendingMerchantItem> recentPendingMerchants
) {
    public AdminDashboard {
        recentPendingMerchants = recentPendingMerchants == null ? List.of() : List.copyOf(recentPendingMerchants);
    }
}
