package com.merqo.pos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.merqo.common.money.Money;

/**
 * Pure Java unit tests for {@link ApprovalPinPolicy}, {@link ApprovalTokenRules}, and {@link
 * CashSessionReconciler} (no Spring).
 */
class ApprovalAndSessionDomainTest {

    @Nested
    @DisplayName("ApprovalPinPolicy")
    class PinPolicyTests {

        @Test
        @DisplayName("accepts 4 to 8 non-trivial digits")
        void acceptsStrongPins() {
            ApprovalPinPolicy.validate("4829");
            ApprovalPinPolicy.validate("907142");
            ApprovalPinPolicy.validate("73918264");
        }

        @Test
        @DisplayName("rejects wrong length, non-digits, all-same digits, and straight sequences")
        void rejectsWeakPins() {
            String[] badPins = {
                "123", "123456789", "12ab", "1111", "000000", "1234", "4321", "987654"
            };
            for (String pin : badPins) {
                PosRuleException ex =
                        assertThrows(PosRuleException.class, () -> ApprovalPinPolicy.validate(pin));
                assertEquals("PIN_POLICY", ex.getErrorCode());
            }
        }

        @Test
        @DisplayName("allows demo PIN 1234 only when demo bypass flag is true")
        void demoBypass() {
            ApprovalPinPolicy.validate("1234", true);
        }
    }

    @Nested
    @DisplayName("ApprovalTokenRules")
    class TokenRulesTests {

        @Test
        @DisplayName(
                "enforces unused, unexpired (Clock), matching cashier/location, and discount ceiling")
        void tokenChecks() {
            UUID cashierId = UUID.randomUUID();
            UUID locationId = UUID.randomUUID();
            Instant created = Instant.parse("2026-11-27T10:00:00Z");
            Instant expires = created.plus(ApprovalTokenRules.TOKEN_TTL);
            Clock beforeExpiry = Clock.fixed(expires.minusSeconds(1), ZoneOffset.UTC);
            Clock atExpiry = Clock.fixed(expires, ZoneOffset.UTC);

            ApprovalTokenRules.TokenSnapshot valid =
                    new ApprovalTokenRules.TokenSnapshot(
                            cashierId, locationId, new BigDecimal("25.00"), expires, null);

            assertTrue(
                    ApprovalTokenRules.isValid(
                            valid, cashierId, locationId, new BigDecimal("25.00"), beforeExpiry));
            assertFalse(
                    ApprovalTokenRules.isValid(
                            valid, cashierId, locationId, new BigDecimal("25.01"), beforeExpiry));
            assertFalse(
                    ApprovalTokenRules.isValid(
                            valid,
                            UUID.randomUUID(),
                            locationId,
                            new BigDecimal("10.00"),
                            beforeExpiry));
            assertFalse(
                    ApprovalTokenRules.isValid(
                            valid,
                            cashierId,
                            UUID.randomUUID(),
                            new BigDecimal("10.00"),
                            beforeExpiry));
            assertFalse(
                    ApprovalTokenRules.isValid(
                            valid, cashierId, locationId, new BigDecimal("10.00"), atExpiry));

            ApprovalTokenRules.TokenSnapshot used =
                    new ApprovalTokenRules.TokenSnapshot(
                            cashierId,
                            locationId,
                            new BigDecimal("25.00"),
                            expires,
                            created.plusSeconds(30));
            assertFalse(
                    ApprovalTokenRules.isValid(
                            used, cashierId, locationId, new BigDecimal("10.00"), beforeExpiry));
        }
    }

    @Nested
    @DisplayName("CashSessionReconciler")
    class ReconcilerTests {

        @Test
        @DisplayName("computes expected totals with cash and non-cash refunds and variances")
        void reconcilesWithRefunds() {
            CashSessionReconciler.ExpectedTotals expected =
                    CashSessionReconciler.expected(
                            Money.of(5000),
                            List.of(
                                    new CashSessionReconciler.MethodAmount(
                                            PaymentMethod.CASH, Money.of(1200)),
                                    new CashSessionReconciler.MethodAmount(
                                            PaymentMethod.BKASH, Money.of(800)),
                                    new CashSessionReconciler.MethodAmount(
                                            PaymentMethod.CARD, Money.of(500))),
                            List.of(
                                    new CashSessionReconciler.MethodAmount(
                                            PaymentMethod.CASH, Money.of(200)),
                                    new CashSessionReconciler.MethodAmount(
                                            PaymentMethod.BKASH, Money.of(100))));

            assertEquals(Money.of(6000), expected.get(PaymentMethod.CASH));
            assertEquals(Money.of(700), expected.get(PaymentMethod.BKASH));
            assertEquals(Money.ZERO, expected.get(PaymentMethod.NAGAD));
            assertEquals(Money.of(500), expected.get(PaymentMethod.CARD));

            CashSessionReconciler.ReconciliationResult balanced =
                    CashSessionReconciler.reconcile(
                            expected,
                            Map.of(
                                    PaymentMethod.CASH, Money.of(6000),
                                    PaymentMethod.BKASH, Money.of(700),
                                    PaymentMethod.NAGAD, Money.ZERO,
                                    PaymentMethod.CARD, Money.of(500)));
            assertFalse(balanced.anyDifferenceNonZero());

            CashSessionReconciler.ReconciliationResult shortCash =
                    CashSessionReconciler.reconcile(
                            expected,
                            Map.of(
                                    PaymentMethod.CASH, Money.of(5950),
                                    PaymentMethod.BKASH, Money.of(700),
                                    PaymentMethod.NAGAD, Money.ZERO,
                                    PaymentMethod.CARD, Money.of(500)));
            assertTrue(shortCash.anyDifferenceNonZero());
            assertEquals(Money.of(-50), shortCash.byMethod().get(PaymentMethod.CASH).difference());
        }
    }
}
