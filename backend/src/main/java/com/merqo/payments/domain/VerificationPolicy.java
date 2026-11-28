package com.merqo.payments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure domain authorization and four-eyes check for deciding a recorded order payment.
 *
 * <p>Responsibility: enforce that non-owners hold {@code PAYMENT_VERIFY}, and when {@code
 * payments.require_separate_verifier} is enabled, prohibit a staff member from verifying a payment
 * they recorded themselves.
 *
 * <p>OOP concept: <b>Policy Object</b> — isolates verification authorization rules in a stateless
 * component independent of persistence or web frameworks.
 */
public final class VerificationPolicy {

    private VerificationPolicy() {}

    /**
     * Evaluates whether an actor may decide a recorded payment, throwing on violation.
     *
     * @param actorId caller user ID
     * @param isOwner whether caller is the business owner
     * @param hasPaymentVerify whether caller holds {@code PAYMENT_VERIFY} at the order location
     * @param recordedBy user ID of the staff member who recorded the payment
     * @param requireSeparateVerifier value of {@code payments.require_separate_verifier}
     */
    public static void assertCanDecide(
            UUID actorId,
            boolean isOwner,
            boolean hasPaymentVerify,
            UUID recordedBy,
            boolean requireSeparateVerifier) {
        if (isOwner) {
            return;
        }
        if (!hasPaymentVerify) {
            throw new PaymentRuleException(
                    "FORBIDDEN", 403, "You do not have permission to verify payments here.");
        }
        if (requireSeparateVerifier && actorId != null && Objects.equals(actorId, recordedBy)) {
            throw new PaymentRuleException(
                    "SELF_VERIFICATION_NOT_ALLOWED",
                    422,
                    "Separate verifier policy forbids deciding a payment you recorded.");
        }
    }
}
