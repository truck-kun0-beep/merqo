package com.merqo.orders.domain;

import java.util.Locale;

import com.merqo.common.money.Money;

/**
 * Value object describing customer payment terms for an online or manual order.
 *
 * <p>Responsibility: hold payment mode ({@code FULL_PREPAID}, {@code COD}, {@code ADVANCE_COD}),
 * advance and COD amounts, and optional unverified claimed advance details, and validate that the
 * amounts reconcile strictly against the order total.
 *
 * <p>Phase 4 records claimed advances only; verification happens in Phase 5.
 *
 * @param mode prepaid, cash-on-delivery, or partial advance + COD
 * @param advanceAmount amount claimed as already paid (zero for COD)
 * @param codAmount amount due on delivery (zero for FULL_PREPAID)
 * @param claimedAdvanceMethod optional unverified channel (BKASH, NAGAD, CARD, BANK)
 * @param claimedAdvanceReference optional unverified transaction reference
 */
public record PaymentTerms(
        PaymentMode mode,
        Money advanceAmount,
        Money codAmount,
        ClaimedAdvanceMethod claimedAdvanceMethod,
        String claimedAdvanceReference) {

    /** Payment collection modes supported for orders. */
    public enum PaymentMode {
        FULL_PREPAID,
        COD,
        ADVANCE_COD;

        public static PaymentMode fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new OrderRuleException("VALIDATION_FAILED", 422, "Payment mode is required.");
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new OrderRuleException(
                        "VALIDATION_FAILED", 422, "Unsupported payment mode: " + raw);
            }
        }
    }

    /** Unverified channels a customer may claim for an advance payment. */
    public enum ClaimedAdvanceMethod {
        BKASH,
        NAGAD,
        CARD,
        BANK;

        public static ClaimedAdvanceMethod fromNullable(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new OrderRuleException(
                        "VALIDATION_FAILED", 422, "Unsupported claimed advance method: " + raw);
            }
        }
    }

    /**
     * Validates payment term invariants against the order total.
     *
     * @param orderTotal total order payable amount
     */
    public void validateAgainst(Money orderTotal) {
        if (mode == null || advanceAmount == null || codAmount == null || orderTotal == null) {
            throw new OrderRuleException(
                    "VALIDATION_FAILED",
                    422,
                    "Payment mode, advance, and COD amounts are required.");
        }
        if (advanceAmount.isNegative() || codAmount.isNegative() || orderTotal.isNegative()) {
            throw new OrderRuleException(
                    "VALIDATION_FAILED", 422, "Payment amounts must not be negative.");
        }
        switch (mode) {
            case FULL_PREPAID -> {
                if (!advanceAmount.equals(orderTotal) || !codAmount.isZero()) {
                    throw new OrderRuleException(
                            "VALIDATION_FAILED",
                            422,
                            "FULL_PREPAID requires advanceAmount == total and codAmount == 0.00.");
                }
            }
            case COD -> {
                if (!advanceAmount.isZero() || !codAmount.equals(orderTotal)) {
                    throw new OrderRuleException(
                            "VALIDATION_FAILED",
                            422,
                            "COD requires advanceAmount == 0.00 and codAmount == total.");
                }
            }
            case ADVANCE_COD -> {
                if (advanceAmount.isZero()
                        || advanceAmount.compareTo(orderTotal) >= 0
                        || !advanceAmount.plus(codAmount).equals(orderTotal)) {
                    throw new OrderRuleException(
                            "VALIDATION_FAILED",
                            422,
                            "ADVANCE_COD requires 0 < advanceAmount < total and advanceAmount + codAmount == total.");
                }
            }
        }
    }

    /**
     * Whether any advance payment was claimed (used when cancelling orders so Phase 5 knows a
     * refund review may be required).
     *
     * @return {@code true} when {@code advanceAmount > 0}
     */
    public boolean hasClaimedAdvance() {
        return advanceAmount != null && !advanceAmount.isZero();
    }
}
