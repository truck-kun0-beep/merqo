package com.merqo.common.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link Money}.
 *
 * <p>These tests run without Spring: no application context, no database. They prove the rounding
 * rules that every later module relies on.
 */
class MoneyTest {

    @Nested
    @DisplayName("creation and scale")
    class CreationAndScale {

        @Test
        @DisplayName("keeps two decimal places")
        void keepsTwoDecimalPlaces() {
            Money money = Money.of(new BigDecimal("1250.5"));

            assertEquals(Money.SCALE, money.amount().scale());
            assertEquals(new BigDecimal("1250.50"), money.amount());
        }

        @Test
        @DisplayName("rounds half up at the third decimal place")
        void roundsHalfUp() {
            Money money = Money.of(new BigDecimal("10.005"));

            assertEquals(new BigDecimal("10.01"), money.amount());
        }

        @Test
        @DisplayName("rounds a value just below the half up point down")
        void roundsDownBelowHalf() {
            Money money = Money.of(new BigDecimal("10.004"));

            assertEquals(new BigDecimal("10.00"), money.amount());
        }

        @Test
        @DisplayName("rejects a null value")
        void rejectsNull() {
            assertThrows(IllegalArgumentException.class, () -> Money.of((BigDecimal) null));
        }

        @Test
        @DisplayName("parses text amounts")
        void parsesText() {
            assertEquals(new BigDecimal("99.99"), Money.parse(" 99.99 ").amount());
        }

        @Test
        @DisplayName("rejects blank text")
        void rejectsBlankText() {
            assertThrows(IllegalArgumentException.class, () -> Money.parse("   "));
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("adds two amounts")
        void adds() {
            Money result = Money.of(new BigDecimal("10.25")).plus(Money.of(new BigDecimal("5.75")));

            assertEquals(new BigDecimal("16.00"), result.amount());
        }

        @Test
        @DisplayName("subtracts two amounts")
        void subtracts() {
            Money result =
                    Money.of(new BigDecimal("10.25")).minus(Money.of(new BigDecimal("0.25")));

            assertEquals(new BigDecimal("10.00"), result.amount());
        }

        @Test
        @DisplayName("multiplies by a quantity and rounds once at the end")
        void multipliesOnce() {
            // 33.33 x 3.003 = 100.08999, which must become 100.09 and not 100.08.
            Money result = Money.of(new BigDecimal("33.33")).multipliedBy(new BigDecimal("3.003"));

            assertEquals(new BigDecimal("100.09"), result.amount());
        }

        @Test
        @DisplayName("rounds the unit price to scale 2 before it is multiplied")
        void roundsUnitPriceBeforeMultiplying() {
            // 33.335 is not a valid money amount, it becomes 33.34, so 3 x 33.34 = 100.02.
            Money result = Money.of(new BigDecimal("33.335")).multipliedBy(new BigDecimal("3"));

            assertEquals(new BigDecimal("100.02"), result.amount());
        }

        @Test
        @DisplayName("multiplies by a fractional quantity")
        void multipliesByFraction() {
            Money result = Money.of(new BigDecimal("100.00")).multipliedBy(new BigDecimal("0.175"));

            assertEquals(new BigDecimal("17.50"), result.amount());
        }

        @Test
        @DisplayName("calculates a percentage of an amount")
        void calculatesPercentage() {
            Money result = Money.of(new BigDecimal("1250.00")).percentageOf(new BigDecimal("10"));

            assertEquals(new BigDecimal("125.00"), result.amount());
        }

        @Test
        @DisplayName("calculates a percentage that needs rounding")
        void calculatesPercentageWithRounding() {
            Money result = Money.of(new BigDecimal("33.33")).percentageOf(new BigDecimal("7.5"));

            assertEquals(new BigDecimal("2.50"), result.amount());
        }

        @Test
        @DisplayName("does not change the receiver when adding")
        void doesNotMutateReceiver() {
            Money original = Money.of(new BigDecimal("10.00"));

            original.plus(Money.of(new BigDecimal("5.00")));

            assertEquals(new BigDecimal("10.00"), original.amount());
        }

        @Test
        @DisplayName("picks the smaller and larger amount")
        void picksMinAndMax() {
            Money small = Money.of(new BigDecimal("10.00"));
            Money large = Money.of(new BigDecimal("20.00"));

            assertEquals(small, small.min(large));
            assertEquals(large, small.max(large));
        }

        @Test
        @DisplayName("turns a negative amount positive")
        void absoluteValue() {
            assertEquals(
                    new BigDecimal("12.50"),
                    Money.of(new BigDecimal("-12.50")).absolute().amount());
        }

        @Test
        @DisplayName("flips the sign")
        void negates() {
            assertEquals(
                    new BigDecimal("-12.50"), Money.of(new BigDecimal("12.50")).negated().amount());
        }
    }

    @Nested
    @DisplayName("comparison and equality")
    class ComparisonAndEquality {

        @Test
        @DisplayName("treats amounts with different scale as equal")
        void equalityIgnoresScale() {
            assertEquals(Money.of(new BigDecimal("10.00")), Money.of(new BigDecimal("10")));
            assertEquals(
                    Money.of(new BigDecimal("10.00")).hashCode(),
                    Money.of(new BigDecimal("10")).hashCode());
        }

        @Test
        @DisplayName("compares amounts by value")
        void comparesByValue() {
            assertTrue(
                    Money.of(new BigDecimal("10.00")).compareTo(Money.of(new BigDecimal("10.01")))
                            < 0);
            assertTrue(
                    Money.of(new BigDecimal("10.02")).compareTo(Money.of(new BigDecimal("10.01")))
                            > 0);
            assertEquals(
                    0,
                    Money.of(new BigDecimal("10.01")).compareTo(Money.of(new BigDecimal("10.01"))));
        }

        @Test
        @DisplayName("is not equal to another type")
        void notEqualToOtherType() {
            assertNotEquals(Money.of(new BigDecimal("10.00")), "10.00");
        }
    }

    @Nested
    @DisplayName("predicates and display")
    class PredicatesAndDisplay {

        @Test
        @DisplayName("detects negative and zero amounts")
        void detectsSigns() {
            assertTrue(Money.of(new BigDecimal("-0.01")).isNegative());
            assertFalse(Money.of(new BigDecimal("0.01")).isNegative());
            assertTrue(Money.ZERO.isZero());
        }

        @Test
        @DisplayName("prints the amount with the currency symbol")
        void printsWithSymbol() {
            assertEquals("৳ 1250.50", Money.of(new BigDecimal("1250.5")).toString());
        }
    }

    @ParameterizedTest(name = "{0} x {1} = {2}")
    @CsvSource({"2.50, 4, 10.00", "0.01, 3, 0.03", "99.99, 2, 199.98", "1250.00, 1, 1250.00"})
    @DisplayName("multiplies everyday amounts exactly")
    void multipliesEverydayAmounts(String unitPrice, int quantity, String expected) {
        Money result =
                Money.of(new BigDecimal(unitPrice)).multipliedBy(BigDecimal.valueOf(quantity));

        assertEquals(0, result.amount().compareTo(new BigDecimal(expected)));
    }
}
