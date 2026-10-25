package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.CurrencyMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    @DisplayName("Should create Money with valid minor units and ISO currency")
    void shouldCreateMoneyWithValidParameters() {
        Money money = Money.ofMinorUnits(50000L, "LKR");

        assertThat(money.getAmountMinorUnits()).isEqualTo(50000L);
        assertThat(money.getCurrency()).isEqualTo("LKR");
        assertThat(money.toString()).isEqualTo("LKR 500.00 (50000 minor units)");
    }

    @Test
    @DisplayName("Should reject negative monetary amounts")
    void shouldRejectNegativeAmount() {
        assertThatThrownBy(() -> Money.ofMinorUnits(-100L, "LKR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Monetary amount cannot be negative");
    }

    @Test
    @DisplayName("Should reject invalid or unrecognized currency codes")
    void shouldRejectInvalidCurrency() {
        assertThatThrownBy(() -> Money.ofMinorUnits(100L, "INVALID"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Money.ofMinorUnits(100L, "ZZZ"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should correctly add two Money instances with same currency")
    void shouldAddMoneyWithSameCurrency() {
        Money base = Money.ofMinorUnits(35000L, "LKR");
        Money surcharge = Money.ofMinorUnits(15000L, "LKR");

        Money total = base.plus(surcharge);

        assertThat(total.getAmountMinorUnits()).isEqualTo(50000L);
        assertThat(total.getCurrency()).isEqualTo("LKR");
    }

    @Test
    @DisplayName("Should reject addition when currencies differ")
    void shouldRejectAdditionWithDifferentCurrencies() {
        Money lkr = Money.ofMinorUnits(50000L, "LKR");
        Money usd = Money.ofMinorUnits(50000L, "USD");

        assertThatThrownBy(() -> lkr.plus(usd))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    @DisplayName("Should correctly multiply Money by decimal factor with HALF_UP rounding")
    void shouldMultiplyWithHalfUpRounding() {
        Money perKg = Money.ofMinorUnits(7500L, "LKR"); // 75.00 LKR
        BigDecimal excessWeight = new BigDecimal("2.35");

        Money result = perKg.multiply(excessWeight);

        // 7500 * 2.35 = 17625.00 -> 17625 minor units
        assertThat(result.getAmountMinorUnits()).isEqualTo(17625L);
        assertThat(result.getCurrency()).isEqualTo("LKR");
    }

    @Test
    @DisplayName("Should throw ArithmeticException on minor units overflow")
    void shouldThrowOnMinorUnitsOverflow() {
        Money large1 = Money.ofMinorUnits(Long.MAX_VALUE - 10, "LKR");
        Money large2 = Money.ofMinorUnits(20L, "LKR");

        assertThatThrownBy(() -> large1.plus(large2))
                .isInstanceOf(ArithmeticException.class);
    }
}
