package com.merqo.catalog.domain;

/**
 * The kind of discount a promotion gives.
 *
 * <p>Responsibility: name the two kinds of discount and say which one it is.
 *
 * <p>OOP concept: a closed enum with behaviour. A percentage is above zero and at most one hundred;
 * a fixed amount is never negative. The rules live in the enum, so a promotion cannot exist in a
 * shape the price calculator cannot handle.
 */
public enum DiscountType {

    /** A percentage of the unit price. */
    PERCENT,

    /** A fixed amount off the unit price. */
    FIXED;

    /**
     * Whether a value is acceptable for this kind of discount.
     *
     * @param value the value
     * @return {@code true} when the value is acceptable
     */
    public boolean accepts(java.math.BigDecimal value) {
        if (value == null) {
            return false;
        }
        return switch (this) {
            case PERCENT ->
                    value.signum() > 0 && value.compareTo(java.math.BigDecimal.valueOf(100)) <= 0;
            case FIXED -> value.signum() >= 0;
        };
    }
}
