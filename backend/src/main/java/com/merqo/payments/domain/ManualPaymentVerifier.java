package com.merqo.payments.domain;

/**
 * Default {@link PaymentVerifier} implementation when {@code PAYMENT_MODE=manual}.
 *
 * <p>Responsibility: return {@link VerificationKind#UNAVAILABLE} for automatic provider checks,
 * directing staff to confirm payments through the manual decision endpoint.
 *
 * <p>OOP concept: <b>Null Object / Manual Strategy</b> — preserves a uniform verifier contract when
 * automated merchant API checks are not active.
 */
public final class ManualPaymentVerifier implements PaymentVerifier {

    @Override
    public String verifierName() {
        return "manual";
    }

    @Override
    public VerificationResult verify(PaymentClaim claim) {
        return VerificationResult.unavailable(
                "Automatic verification is unavailable in manual payment mode. Use manual Verify or Reject instead.");
    }
}
