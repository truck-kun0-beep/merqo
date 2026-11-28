package com.merqo.payments.domain;

import java.util.Locale;

/**
 * One-way immutable verification decisions recorded in {@code payment_decisions}.
 *
 * <p>Responsibility: define the terminal outcomes for a recorded order payment.
 *
 * <p>OOP concept: <b>Type Safety</b> — prevents invalid free-text decisions from entering the
 * append-only ledger.
 */
public enum PaymentDecision {
    VERIFIED,
    REJECTED;

    /**
     * Parses a decision string case-insensitively.
     *
     * @param raw decision string
     * @return matching {@link PaymentDecision}
     */
    public static PaymentDecision fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PaymentRuleException("VALIDATION_FAILED", 422, "Decision is required.");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new PaymentRuleException(
                    "VALIDATION_FAILED", 422, "Unsupported payment decision: " + raw);
        }
    }
}
