package com.merqo.payments.domain;

import java.util.Locale;

/**
 * Payout methods allowed when completing an approved order refund.
 *
 * <p>Responsibility: extend {@link OnlinePaymentMethod} reference validation with {@link #CASH}
 * payout rules (no provider reference; explanatory note required).
 *
 * <p>OOP concept: <b>Polymorphic Behavior &amp; Delegation</b> — delegates electronic channel
 * checks to {@link OnlinePaymentMethod} while enforcing cash payout invariants uniformly.
 */
public enum RefundPayoutMethod {
    BKASH(OnlinePaymentMethod.BKASH),
    NAGAD(OnlinePaymentMethod.NAGAD),
    CARD(OnlinePaymentMethod.CARD),
    BANK(OnlinePaymentMethod.BANK),
    CASH(null);

    private final OnlinePaymentMethod onlineDelegate;

    RefundPayoutMethod(OnlinePaymentMethod onlineDelegate) {
        this.onlineDelegate = onlineDelegate;
    }

    /**
     * Parses a refund payout method case-insensitively.
     *
     * @param raw method code
     * @return the payout method
     */
    public static RefundPayoutMethod fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PaymentRuleException("VALIDATION_FAILED", 422, "Payout method is required.");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new PaymentRuleException(
                    "VALIDATION_FAILED", 422, "Unsupported refund payout method: " + raw);
        }
    }

    /**
     * Validates payout reference and note requirements and returns normalized values.
     *
     * @param rawReference raw reference (must be blank for CASH; required otherwise)
     * @param rawNote optional note (required for CASH)
     * @return normalized payout reference (nullable for CASH) and trimmed note
     */
    public ValidatedPayout validateAndNormalize(String rawReference, String rawNote) {
        CardNumberGuard.rejectCardNumberReference(rawReference);
        CardNumberGuard.rejectCardNumberInText(rawNote);
        String trimmedNote = rawNote == null ? null : rawNote.trim();
        if (trimmedNote != null && trimmedNote.isEmpty()) {
            trimmedNote = null;
        }
        if (this == CASH) {
            if (rawReference != null && !rawReference.isBlank()) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED",
                        422,
                        "Cash refund payouts must not include a provider reference.");
            }
            if (trimmedNote == null) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED", 422, "A note is required for cash refund payouts.");
            }
            return new ValidatedPayout(this, null, trimmedNote);
        }
        String normalizedRef = onlineDelegate.normalizeAndValidateReference(rawReference);
        return new ValidatedPayout(this, normalizedRef, trimmedNote);
    }

    /**
     * Normalized refund payout details.
     *
     * @param method payout method
     * @param normalizedReference normalized payout reference, or {@code null} for CASH
     * @param note normalized explanatory note (nullable for electronic methods)
     */
    public record ValidatedPayout(
            RefundPayoutMethod method, String normalizedReference, String note) {}
}
