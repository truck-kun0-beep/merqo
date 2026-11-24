package com.merqo.catalog.domain;

import com.merqo.common.money.Money;

/**
 * The pricing of one line of a sale or a preview.
 *
 * <p>Responsibility: hold the numbers of one priced line and compute them from the list price, the
 * promotion, the manual discount, the quantity and the tax.
 *
 * <p>The rounding rule, in one sentence: <b>every unit price is rounded to two decimals after the
 * discounts are applied, and only then is it multiplied by the quantity.</b> So a list price of
 * 100.00 with a ten percent discount gives a unit price of 90.00, and three of them cost 270.00. A
 * list price of 33.335 with a ten percent discount gives a unit price of 30.00 after the rounding,
 * and three of them cost 90.00, not 90.01.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Immutability / value object</b> - a record with immutable components. The calculator
 *       builds a new one instead of changing an existing one.
 *   <li><b>Composition</b> - the line holds a {@link Money} for every part of the price, instead of
 *       one confusing amount.
 *   <li><b>Encapsulation</b> - the rounding rule lives in the calculator, not in the callers.
 * </ul>
 *
 * @param listPrice the price before any discount
 * @param promotionDiscount the amount the promotion took off the unit price
 * @param manualDiscount the amount the manual discount took off the unit price
 * @param netUnitPrice the unit price after both discounts
 * @param quantity how many pieces the line holds
 * @param lineSubtotal the net unit price multiplied by the quantity
 * @param tax the tax of the line
 * @param lineTotal the subtotal plus the tax
 * @param belowCost whether the net unit price is under the cost price
 */
public record LinePricing(
        Money listPrice,
        Money promotionDiscount,
        Money manualDiscount,
        Money netUnitPrice,
        int quantity,
        Money lineSubtotal,
        Money tax,
        Money lineTotal,
        boolean belowCost) {

    /**
     * The zero line, used when there is nothing to price.
     *
     * @return the empty pricing
     */
    public static LinePricing empty() {
        return new LinePricing(
                Money.ZERO,
                Money.ZERO,
                Money.ZERO,
                Money.ZERO,
                0,
                Money.ZERO,
                Money.ZERO,
                Money.ZERO,
                false);
    }
}
