package com.merqo.payments.domain;

/**
 * Documented placeholder {@link PaymentVerifier} for live bKash merchant API integration.
 *
 * <p>Responsibility: fail explicitly whenever invoked or selected without merchant credentials, so
 * live verification can be plugged in later without touching callers.
 *
 * <p>OOP concept: <b>Open/Closed Stub</b> — holds the extension point for Phase 5+ live merchant
 * API integration while preventing silent fake confirmations.
 */
public final class BkashPaymentVerifier implements PaymentVerifier {

    public static final String NOT_IMPLEMENTED_MESSAGE =
            "Live payment verification is not implemented: needs merchant API access.";

    @Override
    public String verifierName() {
        return "bkash";
    }

    @Override
    public VerificationResult verify(PaymentClaim claim) {
        throw new IllegalStateException(NOT_IMPLEMENTED_MESSAGE);
    }
}
