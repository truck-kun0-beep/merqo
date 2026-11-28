package com.merqo.payments.domain;

import java.util.Locale;

import com.merqo.common.money.Money;

/**
 * Pure validation rules for requesting an online or manual order refund.
 *
 * <p>Responsibility: enforce positive amount, verified-paid balance presence, {@code refundable}
 * cap ({@code REFUND_EXCEEDS_PAID}), valid {@link RefundReason}, explanatory note requirement for
 * {@code OTHER}, and card-number rejection on notes.
 *
 * <p>OOP concept: <b>Single Responsibility</b> — validates refund invariants cleanly against the
 * derived {@link OrderPaymentPosition}.
 */
public final class RefundRules {

    /** Valid business reasons for requesting a customer refund. */
    public enum RefundReason {
        ORDER_CANCELLED,
        ORDER_DECLINED,
        OVERPAYMENT,
        CUSTOMER_REQUEST,
        OTHER;

        /**
         * Parses a refund reason string case-insensitively.
         *
         * @param raw reason code
         * @return parsed reason
         */
        public static RefundReason fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED", 422, "Refund reason is required.");
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED", 422, "Unsupported refund reason: " + raw);
            }
        }
    }

    private RefundRules() {}

    /**
     * Validates a new refund request against the order's derived payment position.
     *
     * @param position derived payment position for the order
     * @param amount requested refund amount
     * @param reason refund reason
     * @param note optional note (required when reason is {@code OTHER})
     */
    public static void validateRequest(
            OrderPaymentPosition position, Money amount, RefundReason reason, String note) {
        if (position == null) {
            throw new IllegalArgumentException("position must not be null");
        }
        if (amount == null || amount.compareTo(Money.ZERO) <= 0) {
            throw new PaymentRuleException(
                    "VALIDATION_FAILED", 422, "Refund amount must be greater than zero.");
        }
        if (reason == null) {
            throw new PaymentRuleException("VALIDATION_FAILED", 422, "Refund reason is required.");
        }
        CardNumberGuard.rejectCardNumberInText(note);
        if (reason == RefundReason.OTHER && (note == null || note.isBlank())) {
            throw new PaymentRuleException(
                    "REASON_REQUIRED", 422, "A note is required when refund reason is OTHER.");
        }
        if (position.verifiedPaid().compareTo(Money.ZERO) <= 0) {
            throw new PaymentRuleException(
                    "REFUND_EXCEEDS_PAID",
                    409,
                    "Order has no verified payment available to refund.");
        }
        if (amount.compareTo(position.refundable()) > 0) {
            throw new PaymentRuleException(
                    "REFUND_EXCEEDS_PAID",
                    409,
                    "Requested refund exceeds remaining refundable balance of "
                            + position.refundable().amount().toPlainString()
                            + ".");
        }
    }
}
