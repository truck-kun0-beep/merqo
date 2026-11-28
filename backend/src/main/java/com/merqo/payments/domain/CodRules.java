package com.merqo.payments.domain;

import java.time.LocalDate;
import java.util.Locale;

import com.merqo.common.money.Money;
import com.merqo.orders.domain.OrderStatus;

/**
 * Pure validation rules for recording a Cash-on-Delivery collection on an order.
 *
 * <p>Responsibility: enforce that COD collections occur only on {@code CONFIRMED} orders expecting
 * COD, reject over-collection beyond {@code codExpected} ({@code COD_EXCEEDS_EXPECTED}), and keep
 * notes free of card numbers.
 *
 * <p>OOP concept: <b>Single Responsibility</b> — isolates Phase 5 COD collection rules ahead of
 * Phase 6 courier shipment integration.
 */
public final class CodRules {

    /** Supported external courier partners. */
    public enum Courier {
        PATHAO,
        STEADFAST;

        /**
         * Parses a courier name case-insensitively.
         *
         * @param raw courier code
         * @return matching courier
         */
        public static Courier fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new PaymentRuleException("VALIDATION_FAILED", 422, "Courier is required.");
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED", 422, "Unsupported courier: " + raw);
            }
        }
    }

    private CodRules() {}

    /**
     * Validates recording a COD collection for an order.
     *
     * @param orderStatus current order status
     * @param codExpected expected COD amount on the order
     * @param alreadyCollected sum of previously recorded COD collections
     * @param amount new collection amount
     * @param courier courier partner
     * @param collectedOn business date of collection
     * @param note optional remarks
     */
    public static void validateCollection(
            OrderStatus orderStatus,
            Money codExpected,
            Money alreadyCollected,
            Money amount,
            Courier courier,
            LocalDate collectedOn,
            String note) {
        if (courier == null || collectedOn == null) {
            throw new PaymentRuleException(
                    "VALIDATION_FAILED", 422, "Courier and collection date are required.");
        }
        CardNumberGuard.rejectCardNumberInText(note);
        if (orderStatus != OrderStatus.CONFIRMED) {
            throw new PaymentRuleException(
                    "INVALID_TRANSITION",
                    409,
                    "COD collections can only be recorded for CONFIRMED orders.");
        }
        if (codExpected == null || codExpected.compareTo(Money.ZERO) <= 0) {
            throw new PaymentRuleException(
                    "COD_NOT_EXPECTED", 422, "This order does not have a COD amount due.");
        }
        if (amount == null || amount.compareTo(Money.ZERO) <= 0) {
            throw new PaymentRuleException(
                    "VALIDATION_FAILED", 422, "COD collection amount must be greater than zero.");
        }
        Money existing = alreadyCollected == null ? Money.ZERO : alreadyCollected;
        if (existing.plus(amount).compareTo(codExpected) > 0) {
            throw new PaymentRuleException(
                    "COD_EXCEEDS_EXPECTED",
                    409,
                    "Total COD collected would exceed expected COD amount of "
                            + codExpected.amount().toPlainString()
                            + ".");
        }
    }
}
