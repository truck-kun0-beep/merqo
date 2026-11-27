package com.merqo.pos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.merqo.common.money.Money;

/** Pure Java unit tests for {@link PaymentMethod} and {@link PaymentRules} (no Spring). */
class PaymentRulesTest {

    @Test
    @DisplayName("payment methods encapsulate reference and cash drawer behaviour")
    void paymentMethodProperties() {
        assertFalse(PaymentMethod.CASH.requiresReference());
        assertTrue(PaymentMethod.CASH.affectsCashDrawer());
        assertFalse(PaymentMethod.CASH.isGloballyUniqueReference());

        assertTrue(PaymentMethod.BKASH.requiresReference());
        assertFalse(PaymentMethod.BKASH.affectsCashDrawer());
        assertTrue(PaymentMethod.BKASH.isGloballyUniqueReference());

        assertTrue(PaymentMethod.NAGAD.requiresReference());
        assertFalse(PaymentMethod.NAGAD.affectsCashDrawer());
        assertTrue(PaymentMethod.NAGAD.isGloballyUniqueReference());

        assertTrue(PaymentMethod.CARD.requiresReference());
        assertFalse(PaymentMethod.CARD.affectsCashDrawer());
        assertFalse(PaymentMethod.CARD.isGloballyUniqueReference());
    }

    @Nested
    @DisplayName("cash validation")
    class CashRules {

        @Test
        @DisplayName("accepts tendered >= total and computes exact change")
        void computesChange() {
            PaymentRules.ValidatedPayment result =
                    PaymentRules.validate(PaymentMethod.CASH, Money.of(675), Money.of(1000), null);

            assertEquals(PaymentMethod.CASH, result.method());
            assertEquals(Money.of(675), result.amount());
            assertEquals(Money.of(1000), result.tendered());
            assertEquals(Money.of(325), result.changeGiven());
            assertNull(result.normalizedReference());
        }

        @Test
        @DisplayName("rejects tendered below bill total with PAYMENT_INSUFFICIENT")
        void rejectsInsufficientCash() {
            PosRuleException ex =
                    assertThrows(
                            PosRuleException.class,
                            () ->
                                    PaymentRules.validate(
                                            PaymentMethod.CASH,
                                            Money.of(500),
                                            Money.of(499),
                                            null));
            assertEquals("PAYMENT_INSUFFICIENT", ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("bKash and Nagad TrxID validation")
    class MobileBankingRules {

        @Test
        @DisplayName("normalizes TrxID to uppercase")
        void normalizesTrxId() {
            PaymentRules.ValidatedPayment bkash =
                    PaymentRules.validate(
                            PaymentMethod.BKASH, Money.of(500), null, "  ab12cd34ef  ");
            assertEquals("AB12CD34EF", bkash.normalizedReference());
            assertEquals(Money.ZERO, bkash.changeGiven());

            PaymentRules.ValidatedPayment nagad =
                    PaymentRules.validate(PaymentMethod.NAGAD, Money.of(500), null, "ng998877");
            assertEquals("NG998877", nagad.normalizedReference());
        }

        @Test
        @DisplayName("rejects missing or malformed TrxID")
        void rejectsMissingOrMalformedTrxId() {
            PosRuleException missing =
                    assertThrows(
                            PosRuleException.class,
                            () ->
                                    PaymentRules.validate(
                                            PaymentMethod.BKASH, Money.of(100), null, " "));
            assertEquals("PAYMENT_REFERENCE_REQUIRED", missing.getErrorCode());

            PosRuleException tooShort =
                    assertThrows(
                            PosRuleException.class,
                            () ->
                                    PaymentRules.validate(
                                            PaymentMethod.NAGAD, Money.of(100), null, "AB12"));
            assertEquals("VALIDATION_FAILED", tooShort.getErrorCode());
        }
    }

    @Nested
    @DisplayName("card reference and PAN rejection")
    class CardRules {

        @Test
        @DisplayName("accepts last 4 digits or 4-12 char approval code")
        void acceptsLast4OrApprovalCode() {
            PaymentRules.ValidatedPayment last4 =
                    PaymentRules.validate(PaymentMethod.CARD, Money.of(1200), null, "4242");
            assertEquals("4242", last4.normalizedReference());

            PaymentRules.ValidatedPayment approval =
                    PaymentRules.validate(PaymentMethod.CARD, Money.of(1200), null, "auth8812");
            assertEquals("AUTH8812", approval.normalizedReference());
        }

        @Test
        @DisplayName(
                "rejects 13, 16, 19 digit PANs with spaces/dashes and Luhn numbers across every method")
        void rejectsCardNumbersAcrossAllMethods() {
            String[] cardLikes = {
                "4111111111111", // 13 digits
                "4111111111111111", // 16 digits
                "4111111111111111111", // 19 digits
                "4111 1111 1111 1111", // spaces
                "4111-1111-1111-1111", // dashes
                "400000123456" // 12-digit Luhn-valid? let's verify below with a known Luhn-valid
                // 12-digit PAN
            };
            // Replace last with known 12-digit Luhn-valid number: 79927398713 (11) -> 411111111119?
            // Wait: 411111111116:
            // Check: 4 1 1 1 1 1 1 1 1 1 1 6 -> alternate from right: 6, 1*2=2, 1, 1*2=2, 1, 1*2=2,
            // 1, 1*2=2, 1, 1*2=2, 1, 4*2=8 => 6+2+1+2+1+2+1+2+1+2+1+8 = 29; so last digit 7 gives
            // 30!
            cardLikes[5] = "411111111117";
            assertTrue(PaymentRules.passesLuhn("411111111117"));

            for (String candidate : cardLikes) {
                for (PaymentMethod method : PaymentMethod.values()) {
                    PosRuleException ex =
                            assertThrows(
                                    PosRuleException.class,
                                    () ->
                                            PaymentRules.validate(
                                                    method,
                                                    Money.of(100),
                                                    method == PaymentMethod.CASH
                                                            ? Money.of(100)
                                                            : null,
                                                    candidate));
                    assertEquals(
                            "CARD_NUMBER_REJECTED",
                            ex.getErrorCode(),
                            "Expected CARD_NUMBER_REJECTED for method "
                                    + method
                                    + " and "
                                    + candidate);
                }
            }
        }
    }
}
