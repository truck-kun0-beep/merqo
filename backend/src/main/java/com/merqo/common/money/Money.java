package com.merqo.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single place where money arithmetic happens in Merqo.
 *
 * <p>Responsibility: convert raw numbers into a fixed-scale currency amount and perform additions,
 * subtractions, multiplications and percentage maths without ever losing precision.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the scale, rounding mode and currency are private final state. A
 *       caller cannot mutate a {@code Money} after creation.
 *   <li><b>Immutability / value object</b> - every operation returns a new {@code Money}; the
 *       receiver is never changed.
 *   <li><b>Static factory + private constructor</b> - the class controls how an instance is
 *       created, so every instance is valid by construction.
 * </ul>
 *
 * <p>Why {@link BigDecimal}: {@code double} and {@code float} are binary floating point numbers and
 * cannot represent 0.1 exactly. Cash amounts of 100.10 can become 100.09999999999999, which breaks
 * reconciliation.
 *
 * <p>Rounding: all results use {@link RoundingMode#HALF_UP} with scale 2, which matches normal
 * accounting expectations for BDT amounts.
 */
public final class Money implements Comparable<Money> {

    /** Number of decimal places used for every BDT amount. */
    public static final int SCALE = 2;

    /** The currency code used by Merqo. */
    public static final String CURRENCY_CODE = "BDT";

    /** The currency symbol shown to staff and on receipts. */
    public static final String CURRENCY_SYMBOL = "৳";

    /** Zero, pre-allocated because it is used constantly. */
    public static final Money ZERO =
            new Money(BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * Creates a {@code Money} from any number, rounding to scale 2 with HALF_UP.
     *
     * <p>The value is rounded once here, so a {@code Money} always holds a valid currency amount. A
     * unit price of 33.335 therefore becomes 33.34 before it is multiplied by a quantity.
     *
     * @param value the raw amount, never {@code null}
     * @return the money value
     */
    public static Money of(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Money value must not be null");
        }
        return new Money(value.setScale(SCALE, RoundingMode.HALF_UP));
    }

    /**
     * Creates a {@code Money} from a long amount of whole units.
     *
     * @param value the amount in whole currency units
     * @return the money value
     */
    public static Money of(long value) {
        return new Money(BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.UNNECESSARY));
    }

    /**
     * Creates a {@code Money} from a text value such as {@code "1250.50"}.
     *
     * @param value the amount written as text
     * @return the money value
     */
    public static Money parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Money text must not be blank");
        }
        return of(new BigDecimal(value.trim()));
    }

    /**
     * Adds two amounts.
     *
     * @param other the amount to add
     * @return a new {@code Money} holding the sum
     */
    public Money plus(Money other) {
        return of(amount.add(other.amount));
    }

    /**
     * Subtracts one amount from another.
     *
     * @param other the amount to subtract
     * @return a new {@code Money} holding the difference
     */
    public Money minus(Money other) {
        return of(amount.subtract(other.amount));
    }

    /**
     * Multiplies by a plain quantity, for example a number of items.
     *
     * <p>The multiplication happens first at full precision and the result is rounded once at the
     * end, so 3 x 33.335 becomes 100.01 and not 100.00.
     *
     * @param quantity how many times the amount applies
     * @return a new {@code Money} holding the product
     */
    public Money multipliedBy(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("Quantity must not be null");
        }
        return of(amount.multiply(quantity));
    }

    /**
     * Applies a percentage, for example a 10% discount.
     *
     * @param percentage the percentage value, where 10 means ten percent
     * @return a new {@code Money} holding the percentage of this amount
     */
    public Money percentageOf(BigDecimal percentage) {
        if (percentage == null) {
            throw new IllegalArgumentException("Percentage must not be null");
        }
        return of(amount.multiply(percentage).movePointLeft(2));
    }

    /**
     * Returns the smaller of two amounts.
     *
     * @param other the amount to compare with
     * @return the smaller amount
     */
    public Money min(Money other) {
        return amount.compareTo(other.amount) <= 0 ? this : other;
    }

    /**
     * Returns the larger of two amounts.
     *
     * @param other the amount to compare with
     * @return the larger amount
     */
    public Money max(Money other) {
        return amount.compareTo(other.amount) >= 0 ? this : other;
    }

    /**
     * Returns the absolute value, used for reporting negative balances.
     *
     * @return a new {@code Money} that is never negative
     */
    public Money absolute() {
        return of(amount.abs());
    }

    /**
     * Returns this amount with the opposite sign.
     *
     * @return a new {@code Money} holding the negated amount
     */
    public Money negated() {
        return of(amount.negate());
    }

    /**
     * Tells whether this amount is negative.
     *
     * @return {@code true} when the amount is below zero
     */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * Tells whether this amount is zero.
     *
     * @return {@code true} when the amount is exactly zero
     */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    /**
     * The raw amount with scale 2.
     *
     * @return the amount as a {@link BigDecimal}
     */
    public BigDecimal amount() {
        return amount;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Money money)) {
            return false;
        }
        return amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return CURRENCY_SYMBOL + " " + amount.toPlainString();
    }
}
