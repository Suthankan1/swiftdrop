package com.swiftdrop.pricing.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuoteSnapshotTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-26T10:15:00Z");

    @Test
    @DisplayName("Should create valid anonymous quote snapshot")
    void shouldCreateValidAnonymousSnapshot() {
        UUID quoteId = UUID.randomUUID();
        UUID rateVersionId = UUID.randomUUID();
        UUID rateRuleId = UUID.randomUUID();

        QuoteSnapshot snapshot = new QuoteSnapshot(
                quoteId,
                "RATE-2026-V1",
                rateVersionId,
                rateRuleId,
                "ZONE_1",
                "ZONE_2",
                30,
                20,
                15,
                new BigDecimal("2.50"),
                new BigDecimal("1.80"),
                new BigDecimal("2.50"),
                SizeCategory.MEDIUM,
                35000L,
                15000L,
                50000L,
                "LKR",
                null,
                null,
                true,
                CREATED_AT,
                EXPIRES_AT
        );

        assertThat(snapshot.getQuoteId()).isEqualTo(quoteId);
        assertThat(snapshot.getRateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(snapshot.getPickupZone()).isEqualTo("ZONE_1");
        assertThat(snapshot.getDropoffZone()).isEqualTo("ZONE_2");
        assertThat(snapshot.getBaseFeeMinorUnits()).isEqualTo(35000L);
        assertThat(snapshot.getWeightSurchargeMinorUnits()).isEqualTo(15000L);
        assertThat(snapshot.getTotalAmountMinorUnits()).isEqualTo(50000L);
        assertThat(snapshot.getCurrency()).isEqualTo("LKR");
        assertThat(snapshot.isAnonymous()).isTrue();
        assertThat(snapshot.getSubjectSub()).isNull();
        assertThat(snapshot.getTenantId()).isNull();
    }

    @Test
    @DisplayName("Should create valid authenticated quote snapshot bound to subject and tenant")
    void shouldCreateValidAuthenticatedSnapshot() {
        UUID quoteId = UUID.randomUUID();
        UUID rateVersionId = UUID.randomUUID();

        QuoteSnapshot snapshot = new QuoteSnapshot(
                quoteId,
                "RATE-2026-V1",
                rateVersionId,
                null,
                "ZONE_1",
                "ZONE_2",
                10,
                10,
                10,
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                new BigDecimal("0.50"),
                SizeCategory.SMALL,
                20000L,
                0L,
                20000L,
                "LKR",
                "user-sub-123",
                "merchant-org-456",
                false,
                CREATED_AT,
                EXPIRES_AT
        );

        assertThat(snapshot.isAnonymous()).isFalse();
        assertThat(snapshot.getSubjectSub()).isEqualTo("user-sub-123");
        assertThat(snapshot.getTenantId()).isEqualTo("merchant-org-456");
    }

    @Test
    @DisplayName("Should reject invalid expiry timestamps where expiresAt <= createdAt")
    void shouldRejectInvalidExpiryTimestamps() {
        UUID quoteId = UUID.randomUUID();
        UUID rateVersionId = UUID.randomUUID();

        assertThatThrownBy(() -> new QuoteSnapshot(
                quoteId, "RATE-2026-V1", rateVersionId, null,
                "ZONE_1", "ZONE_2", 10, 10, 10,
                new BigDecimal("1.00"), new BigDecimal("0.20"), new BigDecimal("1.00"),
                SizeCategory.SMALL, 20000L, 0L, 20000L, "LKR",
                null, null, true,
                CREATED_AT, CREATED_AT // Same instant
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be strictly after createdAt");

        assertThatThrownBy(() -> new QuoteSnapshot(
                quoteId, "RATE-2026-V1", rateVersionId, null,
                "ZONE_1", "ZONE_2", 10, 10, 10,
                new BigDecimal("1.00"), new BigDecimal("0.20"), new BigDecimal("1.00"),
                SizeCategory.SMALL, 20000L, 0L, 20000L, "LKR",
                null, null, true,
                CREATED_AT, CREATED_AT.minusSeconds(1) // Before
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should evaluate isExpired correctly against Clock")
    void shouldEvaluateExpiryAgainstClock() {
        QuoteSnapshot snapshot = createSampleSnapshot(EXPIRES_AT);

        // Before expiry: 10:14:59Z
        Clock before = Clock.fixed(EXPIRES_AT.minusSeconds(1), ZoneOffset.UTC);
        assertThat(snapshot.isExpired(before)).isFalse();

        // Exactly at expiry: 10:15:00Z -> Expired
        Clock exact = Clock.fixed(EXPIRES_AT, ZoneOffset.UTC);
        assertThat(snapshot.isExpired(exact)).isTrue();

        // After expiry: 10:15:01Z -> Expired
        Clock after = Clock.fixed(EXPIRES_AT.plusSeconds(1), ZoneOffset.UTC);
        assertThat(snapshot.isExpired(after)).isTrue();
    }

    @Test
    @DisplayName("Should reject negative monetary amounts")
    void shouldRejectNegativeMonetaryAmounts() {
        UUID quoteId = UUID.randomUUID();
        UUID rateVersionId = UUID.randomUUID();

        assertThatThrownBy(() -> new QuoteSnapshot(
                quoteId, "RATE-2026-V1", rateVersionId, null,
                "ZONE_1", "ZONE_2", 10, 10, 10,
                new BigDecimal("1.00"), new BigDecimal("0.20"), new BigDecimal("1.00"),
                SizeCategory.SMALL, -1L, 0L, 20000L, "LKR",
                null, null, true,
                CREATED_AT, EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject anonymous quote with subject or authenticated quote without subject")
    void shouldEnforceOwnershipConsistency() {
        UUID quoteId = UUID.randomUUID();
        UUID rateVersionId = UUID.randomUUID();

        // Anonymous with subject -> reject
        assertThatThrownBy(() -> new QuoteSnapshot(
                quoteId, "RATE-2026-V1", rateVersionId, null,
                "ZONE_1", "ZONE_2", 10, 10, 10,
                new BigDecimal("1.00"), new BigDecimal("0.20"), new BigDecimal("1.00"),
                SizeCategory.SMALL, 20000L, 0L, 20000L, "LKR",
                "illegal-subject", null, true,
                CREATED_AT, EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class);

        // Authenticated without subject -> reject
        assertThatThrownBy(() -> new QuoteSnapshot(
                quoteId, "RATE-2026-V1", rateVersionId, null,
                "ZONE_1", "ZONE_2", 10, 10, 10,
                new BigDecimal("1.00"), new BigDecimal("0.20"), new BigDecimal("1.00"),
                SizeCategory.SMALL, 20000L, 0L, 20000L, "LKR",
                null, null, false,
                CREATED_AT, EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private QuoteSnapshot createSampleSnapshot(Instant expiresAt) {
        return new QuoteSnapshot(
                UUID.randomUUID(), "RATE-2026-V1", UUID.randomUUID(), null,
                "ZONE_1", "ZONE_2", 30, 20, 15,
                new BigDecimal("2.50"), new BigDecimal("1.80"), new BigDecimal("2.50"),
                SizeCategory.MEDIUM, 35000L, 15000L, 50000L, "LKR",
                null, null, true,
                CREATED_AT, expiresAt
        );
    }
}
