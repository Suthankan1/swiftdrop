package com.swiftdrop.accountsservice.merchant;

/**
 * Command payload for administrative merchant approval and suspension actions.
 * Supports optimistic concurrency checks via expectedVersion or reviewToken,
 * and mandatory reason strings for suspensions.
 */
public record AdminMerchantDecisionRequest(
        Long expectedVersion,
        String reviewToken,
        String reason
) {
    public Long resolveExpectedVersion() {
        if (expectedVersion != null) {
            return expectedVersion;
        }
        if (reviewToken != null && !reviewToken.isBlank()) {
            try {
                return Long.parseLong(reviewToken.trim());
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        return null;
    }
}
