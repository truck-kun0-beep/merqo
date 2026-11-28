package com.merqo.payments.domain;

import java.util.UUID;

import com.merqo.common.money.Money;

/**
 * Strategy interface for verifying a recorded customer payment against an external or simulated
 * provider ledger.
 *
 * <p>Responsibility: accept a {@link PaymentClaim} and return a structured {@link
 * VerificationResult} without exposing provider-specific transport or table details.
 *
 * <p>OOP concept: <b>Strategy Pattern</b> — decouples payment verification orchestration from
 * concrete manual, mock, or future merchant API verifiers.
 */
public interface PaymentVerifier {

    /**
     * Stable identifier of the verifier implementation (for {@code PROVIDER:<name>}).
     *
     * @return verifier name such as {@code manual}, {@code mock}, or {@code bkash}
     */
    String verifierName();

    /**
     * Checks a recorded payment against this verifier's backing source.
     *
     * @param claim payment details to check
     * @return verification outcome
     */
    VerificationResult verify(PaymentClaim claim);

    /**
     * Immutable payment claim passed to a {@link PaymentVerifier}.
     *
     * @param paymentId order payment ID
     * @param method payment channel
     * @param reference normalized reference/TrxID
     * @param amount expected payment amount
     * @param orderNumber human-readable order number
     */
    record PaymentClaim(
            UUID paymentId,
            OnlinePaymentMethod method,
            String reference,
            Money amount,
            String orderNumber) {}

    /** Provider verification outcome categories. */
    enum VerificationKind {
        CONFIRMED,
        NOT_FOUND,
        AMOUNT_MISMATCH,
        ALREADY_CLAIMED,
        UNAVAILABLE
    }

    /**
     * Result returned by {@link PaymentVerifier#verify(PaymentClaim)}.
     *
     * @param kind outcome category
     * @param providerReference provider transaction identifier when confirmed or mismatched
     * @param message human-readable explanation
     */
    record VerificationResult(VerificationKind kind, String providerReference, String message) {

        public static VerificationResult confirmed(String providerReference, String message) {
            return new VerificationResult(VerificationKind.CONFIRMED, providerReference, message);
        }

        public static VerificationResult notFound(String message) {
            return new VerificationResult(VerificationKind.NOT_FOUND, null, message);
        }

        public static VerificationResult amountMismatch(String providerReference, String message) {
            return new VerificationResult(
                    VerificationKind.AMOUNT_MISMATCH, providerReference, message);
        }

        public static VerificationResult alreadyClaimed(String providerReference, String message) {
            return new VerificationResult(
                    VerificationKind.ALREADY_CLAIMED, providerReference, message);
        }

        public static VerificationResult unavailable(String message) {
            return new VerificationResult(VerificationKind.UNAVAILABLE, null, message);
        }
    }
}
