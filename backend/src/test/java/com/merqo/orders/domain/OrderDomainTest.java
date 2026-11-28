package com.merqo.orders.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.merqo.common.money.Money;

class OrderDomainTest {

    @Test
    @DisplayName("PhoneNumber normalizes BD mobile variants and nulls invalid inputs")
    void phoneNumberNormalizationTable() {
        assertEquals("01712345678", PhoneNumber.parse("+8801712345678").normalized());
        assertEquals("01712345678", PhoneNumber.parse("8801712345678").normalized());
        assertEquals("01712345678", PhoneNumber.parse("01712-345678").normalized());
        assertEquals("01712345678", PhoneNumber.parse("0171 234 5678").normalized());
        assertEquals("01712345678", PhoneNumber.parse("(017) 1234-5678").normalized());
        assertEquals("01912345678", PhoneNumber.parse("01912345678").normalized());

        // Invalid cases preserve raw and set normalized = null
        PhoneNumber tooShort = PhoneNumber.parse("01712345");
        assertEquals("01712345", tooShort.raw());
        assertNull(tooShort.normalized());
        assertFalse(tooShort.isValid());

        assertNull(PhoneNumber.parse("01212345678").normalized()); // wrong prefix 012
        assertNull(PhoneNumber.parse("0171234abcd").normalized()); // letters
        assertNull(PhoneNumber.parse("+88001712345678").normalized()); // duplicate leading zero
        assertNull(PhoneNumber.parse("").normalized());
        assertNull(PhoneNumber.parse(null).normalized());
    }

    @Test
    @DisplayName("OrderStatus soft-hold and hard-reservation semantics match specification")
    void statusHoldSemantics() {
        assertTrue(OrderStatus.PENDING_CONFIRMATION.isSoftHold());
        assertTrue(OrderStatus.FOLLOW_UP.isSoftHold());
        assertFalse(OrderStatus.CONFIRMED.isSoftHold());
        assertFalse(OrderStatus.DECLINED.isSoftHold());
        assertFalse(OrderStatus.CANCELLED.isSoftHold());

        assertFalse(OrderStatus.PENDING_CONFIRMATION.holdsReservation());
        assertFalse(OrderStatus.FOLLOW_UP.holdsReservation());
        assertTrue(OrderStatus.CONFIRMED.holdsReservation());
        assertTrue(OrderStatus.READY_TO_SHIP.holdsReservation());
        assertTrue(OrderStatus.SHIPPED.holdsReservation());
        assertFalse(OrderStatus.DECLINED.holdsReservation());
        assertFalse(OrderStatus.CANCELLED.holdsReservation());
        assertFalse(OrderStatus.DELIVERED.holdsReservation());
    }

    @Test
    @DisplayName("OrderTransitions enforces every transition in and out of every status")
    void transitionMatrixExhaustive() {
        Set<OrderStatus> reservedOrTerminal =
                Set.of(
                        OrderStatus.DECLINED,
                        OrderStatus.CANCELLED,
                        OrderStatus.READY_TO_SHIP,
                        OrderStatus.SHIPPED,
                        OrderStatus.DELIVERED,
                        OrderStatus.REFUSED,
                        OrderStatus.RETURNED);

        for (OrderStatus target : OrderStatus.values()) {
            boolean pendingAllowed =
                    target == OrderStatus.FOLLOW_UP
                            || target == OrderStatus.CONFIRMED
                            || target == OrderStatus.DECLINED
                            || target == OrderStatus.CANCELLED;
            assertEquals(
                    pendingAllowed,
                    OrderTransitions.canTransition(OrderStatus.PENDING_CONFIRMATION, target));
            if (!pendingAllowed) {
                OrderRuleException ex =
                        assertThrows(
                                OrderRuleException.class,
                                () ->
                                        OrderTransitions.requireTransition(
                                                OrderStatus.PENDING_CONFIRMATION, target));
                assertEquals("INVALID_TRANSITION", ex.getErrorCode());
            }

            boolean followUpAllowed =
                    target == OrderStatus.CONFIRMED
                            || target == OrderStatus.DECLINED
                            || target == OrderStatus.CANCELLED;
            assertEquals(
                    followUpAllowed, OrderTransitions.canTransition(OrderStatus.FOLLOW_UP, target));
            if (!followUpAllowed) {
                assertThrows(
                        OrderRuleException.class,
                        () -> OrderTransitions.requireTransition(OrderStatus.FOLLOW_UP, target));
            }

            boolean confirmedAllowed = target == OrderStatus.CANCELLED;
            assertEquals(
                    confirmedAllowed,
                    OrderTransitions.canTransition(OrderStatus.CONFIRMED, target));
            if (!confirmedAllowed) {
                assertThrows(
                        OrderRuleException.class,
                        () -> OrderTransitions.requireTransition(OrderStatus.CONFIRMED, target));
            }

            for (OrderStatus closed : reservedOrTerminal) {
                assertFalse(OrderTransitions.canTransition(closed, target));
                assertThrows(
                        OrderRuleException.class,
                        () -> OrderTransitions.requireTransition(closed, target));
            }
        }
    }

    @Test
    @DisplayName("PaymentTerms validates FULL_PREPAID, COD, and ADVANCE_COD combinations")
    void paymentTermsValidation() {
        Money total = Money.of(1000);

        new PaymentTerms(
                        PaymentTerms.PaymentMode.FULL_PREPAID,
                        Money.of(1000),
                        Money.ZERO,
                        PaymentTerms.ClaimedAdvanceMethod.BKASH,
                        "TRX123")
                .validateAgainst(total);
        new PaymentTerms(PaymentTerms.PaymentMode.COD, Money.ZERO, Money.of(1000), null, null)
                .validateAgainst(total);
        new PaymentTerms(
                        PaymentTerms.PaymentMode.ADVANCE_COD,
                        Money.of(200),
                        Money.of(800),
                        PaymentTerms.ClaimedAdvanceMethod.NAGAD,
                        "NG99")
                .validateAgainst(total);

        assertThrows(
                OrderRuleException.class,
                () ->
                        new PaymentTerms(
                                        PaymentTerms.PaymentMode.FULL_PREPAID,
                                        Money.of(900),
                                        Money.of(100),
                                        null,
                                        null)
                                .validateAgainst(total));
        assertThrows(
                OrderRuleException.class,
                () ->
                        new PaymentTerms(
                                        PaymentTerms.PaymentMode.COD,
                                        Money.of(100),
                                        Money.of(900),
                                        null,
                                        null)
                                .validateAgainst(total));
        assertThrows(
                OrderRuleException.class,
                () ->
                        new PaymentTerms(
                                        PaymentTerms.PaymentMode.ADVANCE_COD,
                                        Money.ZERO,
                                        Money.of(1000),
                                        null,
                                        null)
                                .validateAgainst(total));
        assertThrows(
                OrderRuleException.class,
                () ->
                        new PaymentTerms(
                                        PaymentTerms.PaymentMode.ADVANCE_COD,
                                        Money.of(1000),
                                        Money.ZERO,
                                        null,
                                        null)
                                .validateAgainst(total));
        assertThrows(
                OrderRuleException.class,
                () ->
                        new PaymentTerms(
                                        PaymentTerms.PaymentMode.ADVANCE_COD,
                                        Money.of(200),
                                        Money.of(750),
                                        null,
                                        null)
                                .validateAgainst(total));
    }

    @Test
    @DisplayName("IncomingOrderValidator enforces line bounds and total matching within 0.01")
    void incomingOrderValidatorChecks() {
        IncomingOrder valid =
                new IncomingOrder(
                        "EXT-1",
                        "Tariq",
                        PhoneNumber.parse("01712345678"),
                        "Road 7, Dhanmondi",
                        "Dhaka",
                        List.of(
                                new IncomingOrder.IncomingOrderLine(
                                        "SKU-1", "Tee", 2, Money.parse("500.00"))),
                        Money.parse("60.00"),
                        Money.parse("50.00"),
                        Money.parse("1010.00"),
                        new PaymentTerms(
                                PaymentTerms.PaymentMode.COD,
                                Money.ZERO,
                                Money.parse("1010.00"),
                                null,
                                null),
                        null);
        IncomingOrderValidator.validate(valid);

        // Within 0.01 rounding tolerance succeeds
        IncomingOrder withinPenny =
                new IncomingOrder(
                        "EXT-2",
                        "Tariq",
                        PhoneNumber.parse("01712345678"),
                        "Road 7",
                        "Dhaka",
                        List.of(
                                new IncomingOrder.IncomingOrderLine(
                                        "SKU-1", "Tee", 1, Money.parse("100.00"))),
                        Money.ZERO,
                        Money.ZERO,
                        Money.parse("100.01"),
                        new PaymentTerms(
                                PaymentTerms.PaymentMode.COD,
                                Money.ZERO,
                                Money.parse("100.01"),
                                null,
                                null),
                        null);
        IncomingOrderValidator.validate(withinPenny);

        // Exceeds 0.01 tolerance rejects with ORDER_TOTAL_MISMATCH
        IncomingOrder mismatch =
                new IncomingOrder(
                        "EXT-3",
                        "Tariq",
                        PhoneNumber.parse("01712345678"),
                        "Road 7",
                        "Dhaka",
                        List.of(
                                new IncomingOrder.IncomingOrderLine(
                                        "SKU-1", "Tee", 1, Money.parse("100.00"))),
                        Money.ZERO,
                        Money.ZERO,
                        Money.parse("100.02"),
                        new PaymentTerms(
                                PaymentTerms.PaymentMode.COD,
                                Money.ZERO,
                                Money.parse("100.02"),
                                null,
                                null),
                        null);
        OrderRuleException ex =
                assertThrows(
                        OrderRuleException.class, () -> IncomingOrderValidator.validate(mismatch));
        assertEquals("ORDER_TOTAL_MISMATCH", ex.getErrorCode());

        // > 50 lines rejected
        List<IncomingOrder.IncomingOrderLine> fiftyOne = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fiftyOne.add(new IncomingOrder.IncomingOrderLine("SKU-" + i, "Item", 1, Money.of(10)));
        }
        assertThrows(
                OrderRuleException.class,
                () ->
                        IncomingOrderValidator.validate(
                                new IncomingOrder(
                                        "EXT-51",
                                        "Tariq",
                                        PhoneNumber.parse("01712345678"),
                                        "Road 7",
                                        "Dhaka",
                                        fiftyOne,
                                        Money.ZERO,
                                        Money.ZERO,
                                        Money.of(510),
                                        new PaymentTerms(
                                                PaymentTerms.PaymentMode.COD,
                                                Money.ZERO,
                                                Money.of(510),
                                                null,
                                                null),
                                        null)));
    }

    @Test
    @DisplayName("PhoneRiskPolicy classifies NEW, OK, WATCH, HIGH and lets HIGH override WATCH")
    void phoneRiskTiers() {
        assertEquals(PhoneRiskPolicy.RiskTier.NEW, PhoneRiskPolicy.evaluate(PhoneHistory.EMPTY, 2));
        assertEquals(
                PhoneRiskPolicy.RiskTier.OK,
                PhoneRiskPolicy.evaluate(new PhoneHistory(3, 1, 0, 0, 1, 5), 2));
        assertEquals(
                PhoneRiskPolicy.RiskTier.WATCH,
                PhoneRiskPolicy.evaluate(new PhoneHistory(1, 1, 1, 0, 0, 3), 2));
        assertEquals(
                PhoneRiskPolicy.RiskTier.HIGH,
                PhoneRiskPolicy.evaluate(new PhoneHistory(0, 3, 2, 1, 1, 7), 2));
        // Higher configured threshold downgrades 2 refusals/returns when below threshold
        assertEquals(
                PhoneRiskPolicy.RiskTier.OK,
                PhoneRiskPolicy.evaluate(new PhoneHistory(2, 0, 0, 1, 1, 4), 3));
    }

    @Test
    @DisplayName("OnlineAvailabilityCalculator never returns negative stock")
    void availabilityCalculatorEdgeCases() {
        assertEquals(12, OnlineAvailabilityCalculator.available(20, 3, 2, 3));
        assertEquals(0, OnlineAvailabilityCalculator.available(5, 3, 2, 5));
        assertEquals(0, OnlineAvailabilityCalculator.available(0, 0, 0, 10));
        assertEquals(0, OnlineAvailabilityCalculator.available(2, 5, 1, 0));
    }

    @Test
    @DisplayName("CallResult, DeclineReason, and CancelReason encode follow-up and note rules")
    void callAndReasonRules() {
        assertFalse(CallResult.ANSWERED.expectsFollowUp());
        assertFalse(CallResult.ANSWERED.requiresNote());
        assertTrue(CallResult.NO_ANSWER.expectsFollowUp());
        assertTrue(CallResult.BUSY.expectsFollowUp());
        assertTrue(CallResult.CALL_BACK_REQUESTED.expectsFollowUp());
        assertFalse(CallResult.WRONG_NUMBER.expectsFollowUp());
        assertTrue(CallResult.WRONG_NUMBER.requiresNote());

        assertFalse(DeclineReason.CUSTOMER_DECLINED.requiresNote());
        assertTrue(DeclineReason.OTHER.requiresNote());
        assertFalse(CancelReason.CUSTOMER_REQUESTED.requiresNote());
        assertTrue(CancelReason.OTHER.requiresNote());
    }
}
