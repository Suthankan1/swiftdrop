package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.AmbiguousRateRuleException;
import com.swiftdrop.pricing.error.RateVersionImmutableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftRateRuleValidatorTest {

    private final UUID versionId = UUID.randomUUID();
    private final DraftRateRuleValidator validator = new DraftRateRuleValidator();

    @Test
    void acceptsExistingBaselineBoundsAndExactMinorUnits() {
        RateRule candidate = rule(null, "0.01", "30.00", "15.00", 120000, 10000);
        assertThatCode(() -> validator.validate(version(RateVersionStatus.DRAFT), candidate, List.of()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = RateVersionStatus.class, names = {"ACTIVE", "SUPERSEDED", "ARCHIVED"})
    void rejectsEveryPublishedState(RateVersionStatus status) {
        assertThatThrownBy(() -> validator.validate(version(status), rule(null, "0.01", "1", "0", 1, 0), List.of()))
                .isInstanceOf(RateVersionImmutableException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-0.01,1", "0.001,1", "0.01,30.01", "0.01,1.001"})
    void rejectsOutOfBoundsOrRoundedBands(String min, String max) {
        assertThatThrownBy(() -> validator.validate(version(RateVersionStatus.DRAFT),
                rule(null, min, max, "0", 1, 0), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"-0.01", "0.001", "10000"})
    void rejectsThresholdOutsideExistingColumnRepresentation(String threshold) {
        assertThatThrownBy(() -> validator.validate(version(RateVersionStatus.DRAFT),
                rule(null, "0.01", "1", threshold, 1, 0), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReversedBandThroughExistingDomain() {
        assertThatThrownBy(() -> rule(null, "2", "1", "0", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,-1"})
    void rejectsNegativeFeesThroughExistingMoney(long base, long surcharge) {
        assertThatThrownBy(() -> rule(null, "0.01", "1", "0", base, surcharge))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0.01,1", "1,2", "0.50,0.75", "0.01,30"})
    void rejectsDuplicateInclusiveBoundaryAndContainedOverlaps(String min, String max) {
        RateRule existing = rule(UUID.randomUUID(), "0.01", "1", "0", 1, 0);
        assertThatThrownBy(() -> validator.validate(version(RateVersionStatus.DRAFT),
                rule(null, min, max, "0", 1, 0), List.of(existing)))
                .isInstanceOf(AmbiguousRateRuleException.class);
    }

    @Test
    void permitsSeparatedBandsAndReplacementOfSameDraftRule() {
        UUID ruleId = UUID.randomUUID();
        RateRule existing = rule(ruleId, "0.01", "1.00", "0", 1, 0);
        assertThatCode(() -> validator.validate(version(RateVersionStatus.DRAFT),
                rule(null, "1.01", "2", "0", 2, 0), List.of(existing)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(version(RateVersionStatus.DRAFT),
                rule(ruleId, "0.01", "1", "0", 2, 0), List.of(existing)))
                .doesNotThrowAnyException();
    }

    @Test
    void replacementMustStillCheckEverySibling() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> validator.validate(version(RateVersionStatus.DRAFT),
                rule(id, "0.01", "2", "0", 1, 0), List.of(
                        rule(id, "0.01", "1", "0", 1, 0),
                        rule(UUID.randomUUID(), "1.01", "2", "0", 1, 0))))
                .isInstanceOf(AmbiguousRateRuleException.class);
    }

    @Test
    void separatesRoutesAndSizeCategories() {
        RateRule candidate = rule(null, "0.01", "1", "0", 1, 0);
        RateRule reverse = new RateRule(UUID.randomUUID(), versionId, "ZONE_2", "ZONE_1",
                SizeCategory.OVERSIZED, new BigDecimal("0.01"), BigDecimal.ONE,
                Money.zero(), Money.zero(), BigDecimal.ZERO);
        RateRule differentSize = new RateRule(UUID.randomUUID(), versionId, "ZONE_1", "ZONE_2",
                SizeCategory.SMALL, new BigDecimal("0.01"), BigDecimal.ONE,
                Money.zero(), Money.zero(), BigDecimal.ZERO);
        assertThatCode(() -> validator.validate(version(RateVersionStatus.DRAFT), candidate, List.of(reverse, differentSize)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRuleFromAnotherVersion() {
        RateVersion other = new RateVersion(UUID.randomUUID(), "OTHER", "LKR", RateVersionStatus.DRAFT,
                Instant.EPOCH, null, null, Instant.EPOCH, null, null);
        assertThatThrownBy(() -> validator.validate(other, rule(null, "0.01", "1", "0", 1, 0), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRuleCurrencyDifferentFromParent() {
        RateVersion other = new RateVersion(versionId, "OTHER", "USD", RateVersionStatus.DRAFT,
                Instant.EPOCH, null, null, Instant.EPOCH, null, null);
        assertThatThrownBy(() -> validator.validate(other, rule(null, "0.01", "1", "0", 1, 0), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverflowAtMaximumBandWeight() {
        assertThatThrownBy(() -> validator.validate(version(RateVersionStatus.DRAFT),
                rule(null, "0.01", "30", "0", Long.MAX_VALUE, 1), List.of()))
                .isInstanceOf(ArithmeticException.class);
    }

    private RateVersion version(RateVersionStatus status) {
        return new RateVersion(versionId, "RATE-DRAFT", "LKR", status,
                Instant.EPOCH, null, null, Instant.EPOCH, null, null);
    }

    private RateRule rule(UUID id, String min, String max, String threshold, long base, long surcharge) {
        return new RateRule(id, versionId, "ZONE_1", "ZONE_2", SizeCategory.OVERSIZED,
                new BigDecimal(min), new BigDecimal(max), Money.ofMinorUnits(base), Money.ofMinorUnits(surcharge),
                new BigDecimal(threshold));
    }
}
