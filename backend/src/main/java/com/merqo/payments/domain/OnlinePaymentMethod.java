package com.merqo.payments.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Payment channels supported for online and manual customer orders.
 *
 * <p>Responsibility: hold method-specific reference validation and normalization rules and reject
 * card-number-like inputs across every channel.
 *
 * <p>OOP concept: <b>Enums with Behavior</b> — each channel encapsulates its own reference format
 * and TrxID namespace participation without scattered conditional chains.
 */
public enum OnlinePaymentMethod {
    BKASH(true),
    NAGAD(true),
    CARD(false),
    BANK(false);

    private static final Pattern TRX_ID_PATTERN = Pattern.compile("^[A-Z0-9]{6,20}$");
    private static final Pattern CARD_REF_PATTERN = Pattern.compile("^[A-Z0-9]{4,12}$");
    private static final Pattern BANK_REF_PATTERN =
            Pattern.compile("^(?=.*[A-Z0-9])[A-Z0-9-]{4,30}$");

    private final boolean globalUniqueTrxId;

    OnlinePaymentMethod(boolean globalUniqueTrxId) {
        this.globalUniqueTrxId = globalUniqueTrxId;
    }

    /**
     * Parses a raw channel code case-insensitively.
     *
     * @param raw channel name
     * @return the matching method
     */
    public static OnlinePaymentMethod fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PaymentRuleException("VALIDATION_FAILED", 422, "Payment method is required.");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new PaymentRuleException(
                    "VALIDATION_FAILED", 422, "Unsupported payment method: " + raw);
        }
    }

    /**
     * Whether non-rejected payments via this method share the system-wide TrxID uniqueness index.
     *
     * @return {@code true} for {@code BKASH} and {@code NAGAD}
     */
    public boolean requiresGlobalUniqueReference() {
        return globalUniqueTrxId;
    }

    /**
     * Validates and normalizes the transaction reference for this payment method.
     *
     * @param rawReference raw reference entered by staff or claimed at intake
     * @return normalized upper-case reference
     */
    public String normalizeAndValidateReference(String rawReference) {
        CardNumberGuard.rejectCardNumberReference(rawReference);
        if (rawReference == null || rawReference.isBlank()) {
            throw new PaymentRuleException(
                    "PAYMENT_REFERENCE_REQUIRED", 422, name() + " payment reference is required.");
        }
        String normalized = rawReference.trim().toUpperCase(Locale.ROOT);
        switch (this) {
            case BKASH, NAGAD -> {
                if (!TRX_ID_PATTERN.matcher(normalized).matches()) {
                    throw new PaymentRuleException(
                            "VALIDATION_FAILED",
                            422,
                            name() + " TrxID must be 6 to 20 letters and digits.");
                }
            }
            case CARD -> {
                if (!CARD_REF_PATTERN.matcher(normalized).matches()) {
                    throw new PaymentRuleException(
                            "VALIDATION_FAILED",
                            422,
                            "Card reference must be an approval code (4 to 12 letters/digits) or the last 4 digits.");
                }
            }
            case BANK -> {
                if (!BANK_REF_PATTERN.matcher(normalized).matches()) {
                    throw new PaymentRuleException(
                            "VALIDATION_FAILED",
                            422,
                            "Bank transfer reference must be 4 to 30 letters, digits, or dashes.");
                }
            }
        }
        return normalized;
    }
}
