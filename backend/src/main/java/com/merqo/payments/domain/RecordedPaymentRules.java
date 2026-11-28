package com.merqo.payments.domain;

import com.merqo.common.money.Money;

/**
 * Pure domain rules governing recording a customer payment against an order.
 *
 * <p>Responsibility: verify positive amounts, refuse payments on COD-only orders expecting zero
 * advance ({@code PAYMENT_NOT_EXPECTED}), and cap total non-rejected payments at the order's
 * required prepayment ({@code PAYMENT_EXCEEDS_TOTAL}).
 *
 * <p>OOP concept: <b>Single Responsibility</b> — encapsulates prepayment cap validation without
 * database or controller coupling.
 */
public final class RecordedPaymentRules {

    private RecordedPaymentRules() {}

    /**
     * Validates a candidate recorded payment against order expectations and existing non-rejected
     * payments.
     *
     * @param expected expected prepayment derived from the order
     * @param existingNonRejectedTotal sum of non-rejected ({@code PENDING_VERIFICATION} + {@code
     *     VERIFIED}) payment amounts already recorded for the order
     * @param amount amount of the payment being recorded
     */
    public static void validate(
            ExpectedPrepayment expected, Money existingNonRejectedTotal, Money amount) {
        if (expected == null) {
            throw new IllegalArgumentException("expected must not be null");
        }
        if (amount == null || amount.compareTo(Money.ZERO) <= 0) {
            throw new PaymentRuleException(
                    "VALIDATION_FAILED", 422, "Payment amount must be greater than zero.");
        }
        Money cap = expected.requiredPrepayment();
        if (cap.isZero()) {
            throw new PaymentRuleException(
                    "PAYMENT_NOT_EXPECTED",
                    422,
                    "This COD order does not expect an advance payment.");
        }
        Money alreadyRecorded =
                existingNonRejectedTotal == null ? Money.ZERO : existingNonRejectedTotal;
        if (alreadyRecorded.plus(amount).compareTo(cap) > 0) {
            throw new PaymentRuleException(
                    "PAYMENT_EXCEEDS_TOTAL",
                    422,
                    "Total non-rejected payments would exceed expected prepayment of "
                            + cap.amount().toPlainString()
                            + ".");
        }
    }
}
