package com.swiftdrop.pricing.security;

import com.swiftdrop.pricing.domain.QuoteSnapshot;
import com.swiftdrop.pricing.domain.SizeCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteOwnershipPolicyTest {

    private final QuoteOwnershipPolicy policy = new QuoteOwnershipPolicy();

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-26T10:15:00Z");

    @Test
    @DisplayName("Anonymous quote is retrievable by any caller including anonymous")
    void anonymousQuoteRetrievableByAll() {
        QuoteSnapshot quote = createSnapshot(null, null, true);

        assertThat(policy.canAccessQuote(quote, CallerContext.anonymous())).isTrue();
        assertThat(policy.canAccessQuote(quote, CallerContext.authenticated("user-1", null, Set.of("CUSTOMER")))).isTrue();
    }

    @Test
    @DisplayName("Authenticated customer can retrieve own quote but not other customer quote")
    void customerCanRetrieveOwnQuoteOnly() {
        QuoteSnapshot quoteA = createSnapshot("user-a", null, false);

        CallerContext callerA = CallerContext.authenticated("user-a", null, Set.of("CUSTOMER"));
        CallerContext callerB = CallerContext.authenticated("user-b", null, Set.of("CUSTOMER"));
        CallerContext anonymousCaller = CallerContext.anonymous();

        assertThat(policy.canAccessQuote(quoteA, callerA)).isTrue();
        assertThat(policy.canAccessQuote(quoteA, callerB)).isFalse();
        assertThat(policy.canAccessQuote(quoteA, anonymousCaller)).isFalse();
    }

    @Test
    @DisplayName("Merchant tenant member can retrieve merchant quote; other merchant cannot")
    void merchantTenantIsolation() {
        QuoteSnapshot merchantQuote = createSnapshot("merchant-user-1", "tenant-alpha", false);

        CallerContext sameTenantColleague = CallerContext.authenticated("merchant-user-2", "tenant-alpha", Set.of("MERCHANT"));
        CallerContext differentTenant = CallerContext.authenticated("other-user", "tenant-beta", Set.of("MERCHANT"));

        assertThat(policy.canAccessQuote(merchantQuote, sameTenantColleague)).isTrue();
        assertThat(policy.canAccessQuote(merchantQuote, differentTenant)).isFalse();
    }

    @Test
    @DisplayName("Privileged staff (ADMIN, DISPATCHER) can access any quote")
    void staffCanAccessAnyQuote() {
        QuoteSnapshot privateQuote = createSnapshot("private-user", "private-tenant", false);

        CallerContext admin = CallerContext.authenticated("admin-1", null, Set.of("ADMIN"));
        CallerContext dispatcher = CallerContext.authenticated("disp-1", null, Set.of("DISPATCHER"));

        assertThat(policy.canAccessQuote(privateQuote, admin)).isTrue();
        assertThat(policy.canAccessQuote(privateQuote, dispatcher)).isTrue();
    }

    private QuoteSnapshot createSnapshot(String subject, String tenantId, boolean isAnonymous) {
        return new QuoteSnapshot(
                UUID.randomUUID(), "RATE-2026-V1", UUID.randomUUID(), null,
                "ZONE_1", "ZONE_2", 30, 20, 15,
                new BigDecimal("2.50"), new BigDecimal("1.80"), new BigDecimal("2.50"),
                SizeCategory.MEDIUM, 35000L, 15000L, 50000L, "LKR",
                subject, tenantId, isAnonymous,
                NOW, EXPIRES
        );
    }
}
