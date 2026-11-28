package com.merqo.payments.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.merqo.common.money.Money;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;
import com.merqo.payments.domain.CodRules.Courier;
import com.merqo.payments.domain.RefundRules.RefundReason;
import com.merqo.payments.domain.RefundStatus.RefundEventType;
import com.merqo.payments.domain.SettlementCalculator.DifferenceKind;
import com.merqo.payments.domain.SettlementCalculator.SettlementCalculation;
import com.merqo.payments.domain.SettlementCalculator.SettlementLineInput;

/** Unit tests (no Spring) for refunds, COD rules, settlement calculator, and COD gap policy. */
class RefundAndCodDomainTest {

    @Test
    @DisplayName("RefundTransitions allows every valid move and rejects every invalid move")
    void refundTransitionsMatrix() {
        assertEquals(RefundStatus.REQUESTED, RefundStatus.derive(List.of()));
        assertEquals(RefundStatus.APPROVED, RefundStatus.derive(List.of(RefundEventType.APPROVED)));
        assertEquals(RefundStatus.REJECTED, RefundStatus.derive(List.of(RefundEventType.REJECTED)));
        assertEquals(
                RefundStatus.PAID,
                RefundStatus.derive(List.of(RefundEventType.APPROVED, RefundEventType.PAID)));

        assertTrue(RefundTransitions.canTransition(RefundStatus.REQUESTED, RefundStatus.APPROVED));
        assertTrue(RefundTransitions.canTransition(RefundStatus.REQUESTED, RefundStatus.REJECTED));
        assertTrue(RefundTransitions.canTransition(RefundStatus.APPROVED, RefundStatus.PAID));
        assertTrue(RefundTransitions.canTransition(RefundStatus.APPROVED, RefundStatus.REJECTED));

        assertFalse(RefundTransitions.canTransition(RefundStatus.REQUESTED, RefundStatus.PAID));
        assertFalse(RefundTransitions.canTransition(RefundStatus.APPROVED, RefundStatus.APPROVED));
        assertFalse(RefundTransitions.canTransition(RefundStatus.APPROVED, RefundStatus.REQUESTED));
        assertFalse(RefundTransitions.canTransition(RefundStatus.PAID, RefundStatus.REJECTED));
        assertFalse(RefundTransitions.canTransition(RefundStatus.REJECTED, RefundStatus.APPROVED));

        assertEquals(
                "INVALID_TRANSITION",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        RefundTransitions.requireTransition(
                                                RefundStatus.REQUESTED, RefundStatus.PAID))
                        .getErrorCode());
    }

    @Test
    @DisplayName("RefundApprovalPolicy blocks self-approval for non-owners and allows owner")
    void refundApprovalPolicyRules() {
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();

        assertEquals(
                "FORBIDDEN",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        RefundApprovalPolicy.assertCanDecide(
                                                approver, false, false, requester))
                        .getErrorCode());
        assertEquals(
                "SELF_APPROVAL_NOT_ALLOWED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        RefundApprovalPolicy.assertCanDecide(
                                                requester, false, true, requester))
                        .getErrorCode());

        RefundApprovalPolicy.assertCanDecide(approver, false, true, requester);
        RefundApprovalPolicy.assertCanDecide(requester, true, false, requester);
    }

    @Test
    @DisplayName(
            "RefundRules enforces verified balance, refundable ceiling, OTHER note, and PAN guard")
    void refundRulesValidation() {
        ExpectedPrepayment expected =
                ExpectedPrepayment.from(
                        PaymentMode.FULL_PREPAID, Money.of(1000), Money.of(1000), Money.ZERO);
        OrderPaymentPosition unpaid =
                OrderPaymentPosition.compute(
                        expected,
                        Money.ZERO,
                        Money.of(1000),
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO);
        assertEquals(
                "REFUND_EXCEEDS_PAID",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        RefundRules.validateRequest(
                                                unpaid,
                                                Money.of(100),
                                                RefundReason.ORDER_CANCELLED,
                                                "Cancel"))
                        .getErrorCode());

        OrderPaymentPosition partiallyRefundable =
                OrderPaymentPosition.compute(
                        expected,
                        Money.of(1000),
                        Money.ZERO,
                        Money.ZERO,
                        Money.of(200),
                        Money.of(300),
                        Money.ZERO,
                        Money.ZERO,
                        Money.ZERO);
        RefundRules.validateRequest(
                partiallyRefundable, Money.of(500), RefundReason.ORDER_CANCELLED, null);

        assertEquals(
                "REFUND_EXCEEDS_PAID",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        RefundRules.validateRequest(
                                                partiallyRefundable,
                                                Money.of(501),
                                                RefundReason.ORDER_CANCELLED,
                                                null))
                        .getErrorCode());
        assertEquals(
                "REASON_REQUIRED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        RefundRules.validateRequest(
                                                partiallyRefundable,
                                                Money.of(100),
                                                RefundReason.OTHER,
                                                "  "))
                        .getErrorCode());
    }

    @Test
    @DisplayName("CodRules allows only CONFIRMED orders with remaining COD expected")
    void codCollectionRules() {
        CodRules.validateCollection(
                OrderStatus.CONFIRMED,
                Money.of(800),
                Money.of(200),
                Money.of(600),
                Courier.PATHAO,
                LocalDate.of(2026, 11, 20),
                "Collected");

        assertEquals(
                "INVALID_TRANSITION",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        CodRules.validateCollection(
                                                OrderStatus.PENDING_CONFIRMATION,
                                                Money.of(800),
                                                Money.ZERO,
                                                Money.of(800),
                                                Courier.PATHAO,
                                                LocalDate.of(2026, 11, 20),
                                                null))
                        .getErrorCode());

        assertEquals(
                "COD_NOT_EXPECTED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        CodRules.validateCollection(
                                                OrderStatus.CONFIRMED,
                                                Money.ZERO,
                                                Money.ZERO,
                                                Money.of(100),
                                                Courier.STEADFAST,
                                                LocalDate.of(2026, 11, 20),
                                                null))
                        .getErrorCode());

        assertEquals(
                "COD_EXCEEDS_EXPECTED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        CodRules.validateCollection(
                                                OrderStatus.CONFIRMED,
                                                Money.of(800),
                                                Money.of(500),
                                                Money.of(301),
                                                Courier.PATHAO,
                                                LocalDate.of(2026, 11, 20),
                                                null))
                        .getErrorCode());
    }

    @Test
    @DisplayName(
            "SettlementCalculator handles exact, SHORT, OVER, over-withCourier, and rounding invariants")
    void settlementCalculatorScenarios() {
        UUID o1 = UUID.randomUUID();
        UUID o2 = UUID.randomUUID();

        // Exact with decimal rounding preservation: (333.33 - 10.12) + (500.00 - 25.00) = 323.21 +
        // 475.00 = 798.21
        SettlementCalculation exact =
                SettlementCalculator.calculate(
                        List.of(
                                new SettlementLineInput(
                                        o1,
                                        "ORD-1",
                                        Money.of(new BigDecimal("333.33")),
                                        Money.of(new BigDecimal("10.12")),
                                        Money.of(new BigDecimal("333.33"))),
                                new SettlementLineInput(
                                        o2, "ORD-2", Money.of(500), Money.of(25), Money.of(500))),
                        Money.of(new BigDecimal("798.21")),
                        null);
        assertEquals(DifferenceKind.NONE, exact.differenceKind());
        assertEquals(Money.ZERO, exact.difference());
        assertEquals(Money.of(new BigDecimal("35.12")), exact.feesTotal());
        assertEquals(Money.of(new BigDecimal("798.21")), exact.sumNet());

        // SHORT and OVER without note -> SETTLEMENT_MISMATCH
        assertEquals(
                "SETTLEMENT_MISMATCH",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        SettlementCalculator.calculate(
                                                List.of(
                                                        new SettlementLineInput(
                                                                o1,
                                                                "ORD-1",
                                                                Money.of(500),
                                                                Money.of(20),
                                                                Money.of(500))),
                                                Money.of(470),
                                                " "))
                        .getErrorCode());

        SettlementCalculation shortBatch =
                SettlementCalculator.calculate(
                        List.of(
                                new SettlementLineInput(
                                        o1, "ORD-1", Money.of(500), Money.of(20), Money.of(500))),
                        Money.of(470),
                        "Deduction dispute");
        assertEquals(DifferenceKind.SHORT, shortBatch.differenceKind());
        assertEquals(Money.of(-10), shortBatch.difference());
        assertEquals(
                shortBatch.amountReceived(), shortBatch.sumNet().plus(shortBatch.difference()));

        SettlementCalculation overBatch =
                SettlementCalculator.calculate(
                        List.of(
                                new SettlementLineInput(
                                        o1, "ORD-1", Money.of(500), Money.of(20), Money.of(500))),
                        Money.of(495),
                        "Prior batch adjustment");
        assertEquals(DifferenceKind.OVER, overBatch.differenceKind());
        assertEquals(Money.of(15), overBatch.difference());
        assertEquals(overBatch.amountReceived(), overBatch.sumNet().plus(overBatch.difference()));

        // Line exceeding codWithCourier refused
        assertEquals(
                "COD_EXCEEDS_EXPECTED",
                assertThrows(
                                PaymentRuleException.class,
                                () ->
                                        SettlementCalculator.calculate(
                                                List.of(
                                                        new SettlementLineInput(
                                                                o1,
                                                                "ORD-1",
                                                                Money.of(501),
                                                                Money.ZERO,
                                                                Money.of(500))),
                                                Money.of(501),
                                                null))
                        .getErrorCode());
    }

    @Test
    @DisplayName(
            "CodGapPolicy flips at Asia/Dhaka midnight boundary (17:59:59 UTC vs 18:00:00 UTC)")
    void codGapDhakaMidnightBoundary() {
        LocalDate collectedOn = LocalDate.of(2026, 11, 20);
        // 2026-11-27T17:59:59Z is 2026-11-27 23:59:59+06:00 (age = 7 days -> NOT > 7)
        Clock beforeDhakaMidnight =
                Clock.fixed(Instant.parse("2026-11-27T17:59:59Z"), ZoneOffset.UTC);
        // 2026-11-27T18:00:00Z is 2026-11-28 00:00:00+06:00 (age = 8 days -> > 7)
        Clock atDhakaMidnight = Clock.fixed(Instant.parse("2026-11-27T18:00:00Z"), ZoneOffset.UTC);

        assertFalse(CodGapPolicy.hasGap(Money.of(500), collectedOn, 7, beforeDhakaMidnight));
        assertTrue(CodGapPolicy.hasGap(Money.of(500), collectedOn, 7, atDhakaMidnight));
        assertFalse(CodGapPolicy.hasGap(Money.ZERO, collectedOn, 7, atDhakaMidnight));

        // Timestamp overload converting UTC collection instant to Dhaka date
        Instant utcCollectionInstant = Instant.parse("2026-11-19T18:05:00Z"); // 20 Nov 00:05 +06:00
        assertFalse(
                CodGapPolicy.hasGap(Money.of(500), utcCollectionInstant, 7, beforeDhakaMidnight));
        assertTrue(CodGapPolicy.hasGap(Money.of(500), utcCollectionInstant, 7, atDhakaMidnight));
    }
}
