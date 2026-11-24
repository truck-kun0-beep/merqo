package com.merqo.catalog.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.merqo.common.money.Money;

/**
 * The tests of the price calculator.
 *
 * <p>These tests run without Spring and without a database, because {@link PriceCalculator} is a
 * plain Java class.
 */
class PriceCalculatorTest {

    private static final BigDecimal FIFTEEN = new BigDecimal("15");

    @Nested
    @DisplayName("the tax exclusive price")
    class TaxExclusive {

        @Test
        @DisplayName("adds the tax on top of the subtotal")
        void addsTheTaxOnTop() {
            LinePricing pricing =
                    PriceCalculator.calculate(Money.of(100), null, null, 1, FIFTEEN, false, null);

            assertEquals(Money.of(100), pricing.netUnitPrice());
            assertEquals(Money.of(115), pricing.lineTotal());
            assertEquals(Money.of(15), pricing.tax());
        }

        @Test
        @DisplayName("rounds the unit price before it is multiplied by the quantity")
        void roundsTheUnitPriceFirst() {
            // The list price of 33.335 becomes 33.34 and the discount of 3.335 becomes 3.34,
            // so the unit price is 30.00. Three of them are 90.00, not 90.01.
            LinePricing pricing =
                    PriceCalculator.calculate(
                            Money.of(new BigDecimal("33.335")),
                            Money.of(new BigDecimal("3.335")),
                            null,
                            3,
                            BigDecimal.ZERO,
                            false,
                            null);

            assertEquals(Money.of(new BigDecimal("30.00")), pricing.netUnitPrice());
            assertEquals(Money.of(new BigDecimal("90.00")), pricing.lineSubtotal());
            assertEquals(Money.of(new BigDecimal("90.00")), pricing.lineTotal());
        }
    }

    @Nested
    @DisplayName("the tax inclusive price")
    class TaxInclusive {

        @Test
        @DisplayName("keeps the total the same and takes the tax out of it")
        void keepsTheTotalTheSame() {
            LinePricing pricing =
                    PriceCalculator.calculate(Money.of(115), null, null, 1, FIFTEEN, true, null);

            assertEquals(Money.of(115), pricing.lineTotal());
            assertEquals(Money.of(115), pricing.lineSubtotal());
            // The tax is 115 * 15 / 115 = 15.
            assertEquals(Money.of(15), pricing.tax());
        }

        @Test
        @DisplayName("multiplies the inclusive price by the quantity first")
        void multipliesFirst() {
            LinePricing pricing =
                    PriceCalculator.calculate(Money.of(115), null, null, 3, FIFTEEN, true, null);

            assertEquals(Money.of(345), pricing.lineSubtotal());
            assertEquals(Money.of(45), pricing.tax());
            assertEquals(Money.of(345), pricing.lineTotal());
        }
    }

    @Nested
    @DisplayName("the discounts")
    class Discounts {

        @Test
        @DisplayName("takes the promotion and the manual discount off the unit price")
        void takesBothDiscountsOff() {
            LinePricing pricing =
                    PriceCalculator.calculate(
                            Money.of(100),
                            Money.of(10),
                            Money.of(5),
                            2,
                            BigDecimal.ZERO,
                            false,
                            null);

            assertEquals(Money.of(10), pricing.promotionDiscount());
            assertEquals(Money.of(5), pricing.manualDiscount());
            assertEquals(Money.of(85), pricing.netUnitPrice());
            assertEquals(Money.of(170), pricing.lineSubtotal());
        }

        @Test
        @DisplayName("never makes the price negative")
        void neverMakesThePriceNegative() {
            LinePricing pricing =
                    PriceCalculator.calculate(
                            Money.of(50),
                            Money.of(80),
                            Money.of(10),
                            1,
                            BigDecimal.ZERO,
                            false,
                            null);

            assertEquals(Money.of(0), pricing.netUnitPrice());
        }

        @Test
        @DisplayName("calculates a percentage discount")
        void calculatesAPercentageDiscount() {
            assertEquals(
                    Money.of(10),
                    PriceCalculator.percentDiscount(Money.of(100), new BigDecimal("10")));
        }

        @Test
        @DisplayName("cuts a fixed discount that is bigger than the price down to the price")
        void cutsAFixedDiscountDownToThePrice() {
            assertEquals(
                    Money.of(100), PriceCalculator.fixedDiscount(Money.of(100), Money.of(150)));
        }
    }

    @Nested
    @DisplayName("the cost flag")
    class CostFlag {

        @Test
        @DisplayName("is set when the net price is under the cost")
        void isSetUnderTheCost() {
            LinePricing pricing =
                    PriceCalculator.calculate(
                            Money.of(100),
                            Money.of(30),
                            null,
                            1,
                            BigDecimal.ZERO,
                            false,
                            Money.of(80));

            assertTrue(pricing.belowCost());
        }

        @Test
        @DisplayName("is not set when the net price is over the cost")
        void isNotSetOverTheCost() {
            LinePricing pricing =
                    PriceCalculator.calculate(
                            Money.of(100),
                            Money.of(10),
                            null,
                            1,
                            BigDecimal.ZERO,
                            false,
                            Money.of(80));

            assertFalse(pricing.belowCost());
        }

        @Test
        @DisplayName("is not set when the caller may not see the cost")
        void isNotSetWithoutACost() {
            LinePricing pricing =
                    PriceCalculator.calculate(
                            Money.of(100), null, null, 1, BigDecimal.ZERO, false, null);

            assertFalse(pricing.belowCost());
        }
    }

    @Nested
    @DisplayName("the tax rules")
    class TaxRules {

        @Test
        @DisplayName("the rate of the product wins over the global rate")
        void theProductRateWins() {
            assertEquals(
                    new BigDecimal("5"),
                    TaxPolicy.effectiveRate(new BigDecimal("5"), new BigDecimal("15")));
        }

        @Test
        @DisplayName("the global rate is used when the product has none")
        void theGlobalRateIsUsed() {
            assertEquals(new BigDecimal("15"), TaxPolicy.effectiveRate(null, new BigDecimal("15")));
        }

        @Test
        @DisplayName("the tax of an exclusive price is the rate of the net amount")
        void theTaxOfAnExclusivePrice() {
            assertEquals(new BigDecimal("15.00"), TaxPolicy.taxOn(new BigDecimal("100"), FIFTEEN));
        }

        @Test
        @DisplayName("the tax of an inclusive price is a part of the price")
        void theTaxOfAnInclusivePrice() {
            assertEquals(new BigDecimal("15"), TaxPolicy.taxOf(new BigDecimal("115"), FIFTEEN));
        }
    }
}
