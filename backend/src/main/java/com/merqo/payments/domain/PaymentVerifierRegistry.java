package com.merqo.payments.domain;

import java.util.Locale;

/**
 * Selects the active {@link PaymentVerifier} implementation from {@code PAYMENT_MODE}.
 *
 * <p>Responsibility: default to {@code manual}, select {@code mock} when configured, and fail fast
 * on startup when {@code live} is configured because merchant API integration is unbuilt.
 *
 * <p>OOP concept: <b>Factory / Registry Pattern</b> — centralizes mode-to-strategy resolution so
 * application services depend strictly on {@link PaymentVerifier}.
 */
public final class PaymentVerifierRegistry {

    private final String mode;
    private final PaymentVerifier activeVerifier;

    /**
     * Resolves the active verifier for the configured {@code PAYMENT_MODE}.
     *
     * @param configuredMode mode value ({@code manual}, {@code mock}, or {@code live})
     * @param manualVerifier manual verifier strategy
     * @param mockVerifier mock verifier strategy
     * @param bkashStubVerifier live stub verifier (never activated until merchant API exists)
     */
    public PaymentVerifierRegistry(
            String configuredMode,
            ManualPaymentVerifier manualVerifier,
            MockPaymentVerifier mockVerifier,
            BkashPaymentVerifier bkashStubVerifier) {
        String normalized =
                configuredMode == null || configuredMode.isBlank()
                        ? "manual"
                        : configuredMode.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "manual" -> {
                this.mode = "manual";
                this.activeVerifier = manualVerifier;
            }
            case "mock" -> {
                this.mode = "mock";
                this.activeVerifier = mockVerifier;
            }
            case "live" ->
                    throw new IllegalStateException(BkashPaymentVerifier.NOT_IMPLEMENTED_MESSAGE);
            default ->
                    throw new IllegalStateException(
                            "Unsupported PAYMENT_MODE '"
                                    + configuredMode
                                    + "'. Expected one of: manual, mock, live.");
        }
        if (bkashStubVerifier == null) {
            throw new IllegalArgumentException("bkashStubVerifier must not be null");
        }
    }

    /**
     * Normalized active mode ({@code manual} or {@code mock}).
     *
     * @return the mode name
     */
    public String getMode() {
        return mode;
    }

    /**
     * Active {@link PaymentVerifier} selected by {@code PAYMENT_MODE}.
     *
     * @return the active verifier strategy
     */
    public PaymentVerifier getVerifier() {
        return activeVerifier;
    }

    /**
     * Whether automated provider checks are enabled ({@code PAYMENT_MODE != manual}).
     *
     * @return {@code true} when automatic verification is available
     */
    public boolean isProviderCheckEnabled() {
        return !"manual".equals(mode);
    }
}
