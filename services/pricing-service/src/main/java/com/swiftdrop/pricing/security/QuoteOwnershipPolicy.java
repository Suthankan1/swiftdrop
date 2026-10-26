package com.swiftdrop.pricing.security;

import com.swiftdrop.pricing.domain.QuoteSnapshot;
import org.springframework.stereotype.Component;

/**
 * Enforces ownership isolation for quote retrieval.
 *
 * Rules:
 * - Anonymous quotes are publicly retrievable by UUID.
 * - Authenticated quotes bound to a customer subject can be retrieved by that customer or privileged staff.
 * - Authenticated quotes bound to a merchant tenant can be retrieved by callers sharing that tenantId or privileged staff.
 * - Callers from different subjects/tenants are denied access.
 */
@Component
public class QuoteOwnershipPolicy {

    public boolean canAccessQuote(QuoteSnapshot quote, CallerContext caller) {
        if (quote.isAnonymous()) {
            return true;
        }

        if (caller == null || caller.isAnonymous()) {
            return false;
        }

        if (caller.isPrivilegedStaff()) {
            return true;
        }

        // Match subject
        if (quote.getSubjectSub() != null && quote.getSubjectSub().equals(caller.subject())) {
            return true;
        }

        // Match tenant
        if (quote.getTenantId() != null && caller.tenantId() != null && quote.getTenantId().equals(caller.tenantId())) {
            return true;
        }

        return false;
    }
}
