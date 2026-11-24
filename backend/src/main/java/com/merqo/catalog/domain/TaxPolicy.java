package com.merqo.catalog.domain;

import java.math.BigDecimal;

/**
 * The rules of the tax rate.
 *
 * <p>Responsibility: choose the tax rate of a product and say whether a price already carries the
 * tax or not.
 *
 * <p>Two rules decide the rate:
 *
 * <ol>
 *   <li>The rate of the product wins. A product without a rate of its own falls back to the global
 *       rate of the settings.
 *   <li>A price is either tax inclusive or tax exclusive. The global setting decides which one
 *       Merqo uses, and the calculator is told which one applies.
 * </ol>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the tax rules live here, not in the price calculator and not
 *       in the database access.
 *   <li><b>Null Object</b> - a missing product rate is handled as an empty optional, so the caller
 *       never has to check for {@code null} twice.
 *   <li><b>Pure function</b> - no Spring, no database, so the rule is unit tested on its own.
 * </ul>
 */
public final class TaxPolicy {

    /** The rate used when nothing else is known. */
    public static final BigDecimal ZERO_RATE = BigDecimal.ZERO;

    private TaxPolicy() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Chooses the tax rate of a product.
     *
     * @param productRate the rate of the product, may be {@code null}
     * @param globalRate the rate of the settings
     * @return the rate that applies, never {@code null}
     */
    public static BigDecimal effectiveRate(BigDecimal productRate, BigDecimal globalRate) {
        if (productRate != null) {
            return productRate;
        }
        return globalRate == null ? ZERO_RATE : globalRate;
    }

    /**
     * Whether a price already carries the tax.
     *
     * @param inclusive the global setting
     * @return {@code true} when the prices include the tax
     */
    public static boolean isInclusive(boolean inclusive) {
        return inclusive;
    }

    /**
     * The part of a tax inclusive price that is the tax itself.
     *
     * @param taxInclusiveAmount the part of the price that is the tax
     * @param rate the rate, where 15 means fifteen percent
     * @return the tax amount
     */
    public static BigDecimal taxOf(BigDecimal taxInclusiveAmount, BigDecimal rate) {
        if (taxInclusiveAmount == null || rate == null) {
            return BigDecimal.ZERO;
        }
        // The tax is rate / (100 + rate) of the inclusive amount.
        return taxInclusiveAmount.multiply(rate).divide(rate.add(BigDecimal.valueOf(100)));
    }

    /**
     * The part of a tax exclusive price that is the tax itself.
     *
     * @param netAmount the part of the price without the tax
     * @param rate the rate, where 15 means fifteen percent
     * @return the tax amount
     */
    public static BigDecimal taxOn(BigDecimal netAmount, BigDecimal rate) {
        if (netAmount == null || rate == null) {
            return BigDecimal.ZERO;
        }
        return netAmount.multiply(rate).movePointLeft(2);
    }
}
