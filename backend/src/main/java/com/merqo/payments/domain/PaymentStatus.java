package com.merqo.payments.domain;

/**
 * Derived status of an order payment record.
 *
 * <p>Responsibility: represent the payment's verification state derived from {@code
 * payment_decisions} rather than a mutable column.
 *
 * <p>OOP concept: <b>Derived Value &amp; Encapsulation</b> — maps an optional immutable {@link
 * PaymentDecision} into a closed set of lifecycle states.
 */
public enum PaymentStatus {
    PENDING_VERIFICATION,
    VERIFIED,
    REJECTED;

    /**
     * Derives status from an immutable verification decision, if one exists.
     *
     * @param decision nullable verification decision
     * @return the derived payment status
     */
    public static PaymentStatus derive(PaymentDecision decision) {
        if (decision == null) {
            return PENDING_VERIFICATION;
        }
        return switch (decision) {
            case VERIFIED -> VERIFIED;
            case REJECTED -> REJECTED;
        };
    }
}
