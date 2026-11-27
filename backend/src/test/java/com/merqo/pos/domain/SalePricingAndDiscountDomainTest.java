package com.merqo.pos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.merqo.catalog.domain.DiscountType;
import com.merqo.common.money.Money;

/**
 * Pure Java unit tests for {@link DiscountAllocator}, {@link DiscountLimitPolicy}, and {@link
 * SaleCalculator} (no Spring).
 */
class SalePricingAndDiscountDomainTest {

    @Nested
    @DisplayName("DiscountAllocator largest-remainder allocation")
    class AllocatorTests {

        @Test
        @DisplayName("splits 100.00 evenly 3 ways so parts sum exactly to 100.00")
        void threeWaySplitOfOneHundred() {
            List<Money> parts =
                    DiscountAllocator.allocate(
                            Money.of(100), List.of(Money.of(100), Money.of(100), Money.of(100)));

            assertEquals(3, parts.size());
            assertEquals(Money.parse("33.34"), parts.get(0));
            assertEquals(Money.parse("33.33"), parts.get(1));
            assertEquals(Money.parse("33.33"), parts.get(2));
            assertEquals(Money.of(100), parts.get(0).plus(parts.get(1)).plus(parts.get(2)));
        }

        @Test
        @DisplayName("tie-break gives extra cent to larger line weight when remainders match")
        void tieBreakFavorsLargerWeight() {
            // Weights: 100.00 and 300.00 (total 400.00), discount = 2.00?
            // With discount 0.02 across weights [1.00, 3.00] -> numerators: 2*100=200 (0 rem 200),
            // 2*300=600 (1 rem 200).
            // Same remainder 200! Larger weight (3.00 at index 1) must win the leftover cent.
            List<Money> parts =
                    DiscountAllocator.allocate(
                            Money.parse("0.02"), List.of(Money.of(1), Money.of(3)));

            assertEquals(Money.parse("0.00"), parts.get(0));
            assertEquals(Money.parse("0.02"), parts.get(1));
        }

        @Test
        @DisplayName("preserves exact sum on complex unequal weights")
        void preservesSumAcrossUnequalWeights() {
            Money discount = Money.parse("17.35");
            List<Money> weights =
                    List.of(Money.parse("123.45"), Money.parse("67.89"), Money.parse("41.11"));
            List<Money> parts = DiscountAllocator.allocate(discount, weights);

            Money sum = Money.ZERO;
            for (Money part : parts) {
                sum = sum.plus(part);
            }
            assertEquals(discount, sum);
        }
    }

    @Nested
    @DisplayName("DiscountLimitPolicy boundaries")
    class LimitPolicyTests {

        @Test
        @DisplayName("measures manual discount after promotion against cashier limit")
        void boundariesAroundLimit() {
            // Price after promo = 80.00. Cashier limit = 10%.
            // 10% of 80.00 = 8.00 -> WITHIN_LIMIT
            DiscountLimitPolicy.Evaluation atLimit =
                    DiscountLimitPolicy.evaluate(
                            DiscountType.FIXED,
                            new BigDecimal("8.00"),
                            Money.of(80),
                            true,
                            false,
                            new BigDecimal("10.00"));
            assertEquals(DiscountLimitPolicy.Decision.WITHIN_LIMIT, atLimit.decision());
            assertEquals(new BigDecimal("10.00"), atLimit.effectivePercent());

            // 8.01 is one cent over 10% -> NEEDS_APPROVAL
            DiscountLimitPolicy.Evaluation oneCentOver =
                    DiscountLimitPolicy.evaluate(
                            DiscountType.FIXED,
                            new BigDecimal("8.01"),
                            Money.of(80),
                            true,
                            false,
                            new BigDecimal("10.00"));
            assertEquals(DiscountLimitPolicy.Decision.NEEDS_APPROVAL, oneCentOver.decision());
        }

        @Test
        @DisplayName("rejects fixed discount at or above price after promotion")
        void rejectsFixedAtOrAbovePrice() {
            PosRuleException atPrice =
                    assertThrows(
                            PosRuleException.class,
                            () ->
                                    DiscountLimitPolicy.evaluate(
                                            DiscountType.FIXED,
                                            new BigDecimal("100.00"),
                                            Money.of(100),
                                            true,
                                            true,
                                            new BigDecimal("100")));
            assertEquals("VALIDATION_FAILED", atPrice.getErrorCode());

            PosRuleException abovePrice =
                    assertThrows(
                            PosRuleException.class,
                            () ->
                                    DiscountLimitPolicy.evaluate(
                                            DiscountType.FIXED,
                                            new BigDecimal("120.00"),
                                            Money.of(100),
                                            true,
                                            true,
                                            new BigDecimal("100")));
            assertEquals("VALIDATION_FAILED", abovePrice.getErrorCode());
        }

        @Test
        @DisplayName(
                "requires POS_DISCOUNT permission and grants unlimited to POS_DISCOUNT_APPROVE")
        void enforcesPermissions() {
            PosRuleException noPerm =
                    assertThrows(
                            PosRuleException.class,
                            () ->
                                    DiscountLimitPolicy.evaluate(
                                            DiscountType.PERCENT,
                                            new BigDecimal("5"),
                                            Money.of(100),
                                            false,
                                            false,
                                            new BigDecimal("20")));
            assertEquals("FORBIDDEN", noPerm.getErrorCode());

            DiscountLimitPolicy.Evaluation approver =
                    DiscountLimitPolicy.evaluate(
                            DiscountType.PERCENT,
                            new BigDecimal("50"),
                            Money.of(100),
                            false,
                            true,
                            BigDecimal.ZERO);
            assertEquals(DiscountLimitPolicy.Decision.WITHIN_LIMIT, approver.decision());
        }
    }

    @Nested
    @DisplayName("SaleCalculator")
    class SaleCalculatorTests {

        @Test
        @DisplayName("computes exclusive tax with promotion, line manual, and whole-bill discount")
        void exclusiveTaxWithStackedDiscounts() {
            // Line 1: list 500, promo 50 -> 450 * 2 = 900; line manual 10% -> 90 off -> 810
            // Line 2: list 300, promo 0 -> 300 * 1 = 300; no manual -> 300
            // Pre-bill subtotal = 1110. Whole-bill discount FIXED 110 -> allocated across [810,
            // 300]
            // Final subtotal = 1000. Tax 10% exclusive -> 100. Total = 1100.
            SaleTotals totals =
                    SaleCalculator.calculate(
                            List.of(
                                    new SaleCalculator.SaleLineInput(
                                            UUID.randomUUID(),
                                            Money.of(500),
                                            Money.of(50),
                                            2,
                                            DiscountType.PERCENT,
                                            new BigDecimal("10"),
                                            new BigDecimal("10"),
                                            false,
                                            Money.of(200)),
                                    new SaleCalculator.SaleLineInput(
                                            UUID.randomUUID(),
                                            Money.of(300),
                                            null,
                                            1,
                                            null,
                                            null,
                                            new BigDecimal("10"),
                                            false,
                                            Money.of(150))),
                            DiscountType.FIXED,
                            new BigDecimal("110.00"));

            assertEquals(Money.of(100), totals.promotionDiscount());
            assertEquals(Money.of(200), totals.manualDiscount());
            assertEquals(Money.of(110), totals.billDiscount());
            assertEquals(Money.of(1000), totals.subtotal());
            assertEquals(Money.of(100), totals.tax());
            assertEquals(Money.of(1100), totals.total());
            assertFalse(totals.anyBelowCost());
        }

        @Test
        @DisplayName("computes inclusive tax and below-cost flag")
        void inclusiveTaxAndBelowCost() {
            SaleTotals totals =
                    SaleCalculator.calculate(
                            List.of(
                                    new SaleCalculator.SaleLineInput(
                                            UUID.randomUUID(),
                                            Money.of(115),
                                            Money.of(25),
                                            1,
                                            null,
                                            null,
                                            new BigDecimal("15"),
                                            true,
                                            Money.of(95))),
                            null,
                            null);

            assertEquals(Money.of(90), totals.subtotal());
            assertEquals(Money.of(90), totals.total());
            assertTrue(totals.anyBelowCost());
        }

        @Test
        @DisplayName("rejects empty cart with EMPTY_CART")
        void rejectsEmptyCart() {
            PosRuleException ex =
                    assertThrows(
                            PosRuleException.class,
                            () -> SaleCalculator.calculate(List.of(), null, null));
            assertEquals("EMPTY_CART", ex.getErrorCode());
        }
    }
}
