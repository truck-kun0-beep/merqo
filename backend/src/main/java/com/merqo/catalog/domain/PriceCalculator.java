package com.merqo.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.merqo.common.money.Money;

/**
 * The calculator of the price of a line.
 *
 * <p>Responsibility: turn a list price, an optional promotion, an optional manual discount, a
 * quantity and a tax rate into the numbers of a priced line.
 *
 * <p>The rounding rule, in one sentence: <b>every unit price is rounded to two decimals after the
 * discounts are applied, and only then is it multiplied by the quantity.</b> That is what a shop
 * does on the shelf: the price on the label is the price the customer pays, and the quantity is
 * counted after it.
 *
 * <p>How the tax is handled:
 *
 * <ul>
 *   <li><b>Exclusive</b> - the list price is the price without the tax. The tax is added on top.
 *   <li><b>Inclusive</b> - the list price already carries the tax. The tax is a part of the price
 *       and is subtracted from the total.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - this class does the arithmetic and nothing else. It never
 *       reads the database and it never decides who may buy.
 *   <li><b>Pure function</b> - the same input always gives the same output, so every edge case is
 *       unit tested without Spring.
 *   <li><b>Static factory</b> - the class is used through its methods, so it is never instantiated
 *       and it holds no state.
 *   <li><b>Composition</b> - the answer is a {@link LinePricing} record that carries every part of
 *       the price, instead of one long list of numbers.
 * </ul>
 */
public final class PriceCalculator {

    private PriceCalculator() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Prices a line.
     *
     * @param listPrice the price before any discount
     * @param promotionDiscount the amount the promotion takes off the unit price, may be {@code
     *     null}
     * @param manualDiscount the amount the manual discount takes off the unit price, may be {@code
     *     null}
     * @param quantity how many pieces the line holds
     * @param taxRate the tax rate, where 15 means fifteen percent, may be {@code null} for no tax
     * @param taxInclusive whether the list price already carries the tax
     * @param costPrice the cost of one piece, may be {@code null} when the caller may not see costs
     * @return the priced line
     */
    public static LinePricing calculate(
            Money listPrice,
            Money promotionDiscount,
            Money manualDiscount,
            int quantity,
            BigDecimal taxRate,
            boolean taxInclusive,
            Money costPrice) {

        if (listPrice == null) {
            throw new IllegalArgumentException("The list price must not be null");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("The quantity must not be negative");
        }

        Money promotion = promotionDiscount == null ? Money.ZERO : promotionDiscount;
        Money manual = manualDiscount == null ? Money.ZERO : manualDiscount;

        // The unit price is the list price minus the two discounts, and it is rounded once here.
        Money netUnitPrice = Money.of(listPrice.minus(promotion).minus(manual).amount());
        if (netUnitPrice.isNegative()) {
            // A discount may never make the price negative. The line becomes free instead.
            netUnitPrice = Money.ZERO;
        }

        Money subtotal = netUnitPrice.multipliedBy(BigDecimal.valueOf(quantity));

        BigDecimal rate = taxRate == null ? BigDecimal.ZERO : taxRate;
        Money tax;
        Money total;
        if (taxInclusive) {
            // The price already carries the tax, so the tax is a part of the subtotal.
            BigDecimal taxPart =
                    TaxPolicy.taxOf(subtotal.amount(), rate).setScale(2, RoundingMode.HALF_UP);
            tax = Money.of(taxPart);
            total = Money.of(subtotal.amount());
        } else {
            BigDecimal taxPart =
                    TaxPolicy.taxOn(subtotal.amount(), rate).setScale(2, RoundingMode.HALF_UP);
            tax = Money.of(taxPart);
            total = subtotal.plus(tax);
        }

        boolean belowCost = costPrice != null && netUnitPrice.compareTo(costPrice) < 0;

        return new LinePricing(
                listPrice,
                promotion,
                manual,
                netUnitPrice,
                quantity,
                subtotal,
                tax,
                total,
                belowCost);
    }

    /**
     * The discount of a percentage promotion.
     *
     * @param unitPrice the price of one piece
     * @param percent the percentage, where 10 means ten percent
     * @return the amount the promotion takes off the unit price
     */
    public static Money percentDiscount(Money unitPrice, BigDecimal percent) {
        if (unitPrice == null || percent == null) {
            return Money.ZERO;
        }
        return unitPrice.percentageOf(percent);
    }

    /**
     * The discount of a fixed promotion.
     *
     * @param unitPrice the price of one piece
     * @param amount the amount the promotion takes off the unit price
     * @return the amount the promotion takes off the unit price
     */
    public static Money fixedDiscount(Money unitPrice, Money amount) {
        if (unitPrice == null || amount == null) {
            return Money.ZERO;
        }
        Money discount = amount;
        // A fixed discount may never be bigger than the price itself.
        return discount.compareTo(unitPrice) > 0 ? unitPrice : discount;
    }
}
