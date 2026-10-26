package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.CurrencyMismatchException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable value object representing a monetary amount in integer minor units (e.g. cents)
 * and an ISO 4217 currency code.
 *
 * Implements DEC-PRC-01 (LKR currency) and DEC-PRC-02 (Integer minor units).
 * Eliminates IEEE 754 floating-point rounding errors and protects against arithmetic overflow.
 */
public final class Money implements Comparable<Money> {

    public static final String DEFAULT_CURRENCY = "LKR";
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    private final long amountMinorUnits;
    private final String currency;

    private Money(long amountMinorUnits, String currency) {
        if (amountMinorUnits < 0) {
            throw new IllegalArgumentException("Monetary amount cannot be negative: " + amountMinorUnits);
        }
        this.amountMinorUnits = amountMinorUnits;
        this.currency = validateCurrency(currency);
    }

    public static Money ofMinorUnits(long amountMinorUnits, String currency) {
        return new Money(amountMinorUnits, currency);
    }

    public static Money ofMinorUnits(long amountMinorUnits) {
        return new Money(amountMinorUnits, DEFAULT_CURRENCY);
    }

    public static Money zero(String currency) {
        return new Money(0L, currency);
    }

    public static Money zero() {
        return new Money(0L, DEFAULT_CURRENCY);
    }

    public long getAmountMinorUnits() {
        return amountMinorUnits;
    }

    public String getCurrency() {
        return currency;
    }

    public Money plus(Money other) {
        Objects.requireNonNull(other, "Cannot add null Money");
        validateSameCurrency(other);
        long sum = Math.addExact(this.amountMinorUnits, other.amountMinorUnits);
        return new Money(sum, this.currency);
    }

    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "Factor cannot be null");
        if (factor.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Multiplication factor cannot be negative: " + factor);
        }
        BigDecimal result = BigDecimal.valueOf(this.amountMinorUnits)
                .multiply(factor)
                .setScale(0, RoundingMode.HALF_UP);
        return new Money(result.longValueExact(), this.currency);
    }

    private void validateSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new CurrencyMismatchException(
                    "Currency mismatch: cannot operate on " + this.currency + " and " + other.currency);
        }
    }

    private static String validateCurrency(String currencyCode) {
        Objects.requireNonNull(currencyCode, "Currency code cannot be null");
        String trimmed = currencyCode.trim().toUpperCase();
        if (!CURRENCY_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid ISO 4217 currency format: " + currencyCode);
        }
        try {
            Currency.getInstance(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency code: " + currencyCode, e);
        }
        return trimmed;
    }

    @Override
    public int compareTo(Money other) {
        Objects.requireNonNull(other, "Cannot compare to null Money");
        validateSameCurrency(other);
        return Long.compare(this.amountMinorUnits, other.amountMinorUnits);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return amountMinorUnits == money.amountMinorUnits && Objects.equals(currency, money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountMinorUnits, currency);
    }

    @Override
    public String toString() {
        return currency + " " + (amountMinorUnits / 100) + "." + String.format("%02d", Math.abs(amountMinorUnits % 100)) + " (" + amountMinorUnits + " minor units)";
    }
}
