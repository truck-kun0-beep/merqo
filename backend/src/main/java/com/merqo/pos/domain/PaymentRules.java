package com.merqo.pos.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import com.merqo.common.money.Money;

/**
 * Pure validation rules for POS payments.
 *
 * <p>Responsibility: validate a payment against the bill total, enforce reference formats per
 * {@link PaymentMethod}, normalize mobile banking TrxIDs to upper case, compute cash change, and
 * reject full card numbers across every payment method.
 *
 * <p>OOP concept: <b>Single Responsibility &amp; Value Object</b> — encapsulates all payment
 * validation rules in a stateless domain class returning an immutable {@link ValidatedPayment}
 * record.
 */
public final class PaymentRules {

    private static final Pattern TRX_ID_PATTERN = Pattern.compile("^[A-Z0-9]{6,20}$");
    private static final Pattern CARD_REF_PATTERN = Pattern.compile("^[A-Z0-9]{4,12}$");
    private static final Pattern ALL_DIGITS = Pattern.compile("^\\d+$");

    private PaymentRules() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Normalized, validated payment details ready for persistence.
     *
     * @param method the payment method
     * @param amount the bill total paid
     * @param tendered the cash tendered (equal to {@code amount} for non-cash methods)
     * @param changeGiven change returned to the customer (always zero for non-cash methods)
     * @param normalizedReference normalized reference, or {@code null} for cash
     */
    public record ValidatedPayment(
            PaymentMethod method,
            Money amount,
            Money tendered,
            Money changeGiven,
            String normalizedReference) {}

    /**
     * Validates a single-method payment against the bill total.
     *
     * @param method the payment method
     * @param billTotal the server-computed bill total
     * @param tendered the cash amount tendered (required for {@link PaymentMethod#CASH})
     * @param rawReference the TrxID or card approval/last-4 reference
     * @return the validated and normalized payment details
     */
    public static ValidatedPayment validate(
            PaymentMethod method, Money billTotal, Money tendered, String rawReference) {
        if (method == null) {
            throw new PosRuleException("VALIDATION_FAILED", 422, "Payment method is required.");
        }
        if (billTotal == null || billTotal.isNegative()) {
            throw new PosRuleException(
                    "VALIDATION_FAILED", 422, "Bill total must not be negative.");
        }

        // Card-number check applies to EVERY method so a full PAN can never sneak into any field.
        if (looksLikeCardNumber(rawReference)) {
            throw new PosRuleException(
                    "CARD_NUMBER_REJECTED",
                    422,
                    "Full card numbers are never accepted. Enter only the last 4 digits or approval code.");
        }

        return switch (method) {
            case CASH -> validateCash(billTotal, tendered);
            case BKASH, NAGAD -> validateMobileTrxId(method, billTotal, rawReference);
            case CARD -> validateCard(billTotal, rawReference);
        };
    }

    private static ValidatedPayment validateCash(Money billTotal, Money tendered) {
        if (tendered == null) {
            throw new PosRuleException(
                    "PAYMENT_INSUFFICIENT", 422, "Cash amount tendered is required.");
        }
        if (tendered.compareTo(billTotal) < 0) {
            throw new PosRuleException(
                    "PAYMENT_INSUFFICIENT", 422, "Cash tendered must be at least the bill total.");
        }
        Money change = tendered.minus(billTotal);
        return new ValidatedPayment(PaymentMethod.CASH, billTotal, tendered, change, null);
    }

    private static ValidatedPayment validateMobileTrxId(
            PaymentMethod method, Money billTotal, String rawReference) {
        if (rawReference == null || rawReference.isBlank()) {
            throw new PosRuleException(
                    "PAYMENT_REFERENCE_REQUIRED", 422, method.name() + " TrxID is required.");
        }
        String normalized = rawReference.trim().toUpperCase(Locale.ROOT);
        if (!TRX_ID_PATTERN.matcher(normalized).matches()) {
            throw new PosRuleException(
                    "VALIDATION_FAILED",
                    422,
                    method.name() + " TrxID must be 6 to 20 letters and digits.");
        }
        return new ValidatedPayment(method, billTotal, billTotal, Money.ZERO, normalized);
    }

    private static ValidatedPayment validateCard(Money billTotal, String rawReference) {
        if (rawReference == null || rawReference.isBlank()) {
            throw new PosRuleException(
                    "PAYMENT_REFERENCE_REQUIRED",
                    422,
                    "Card approval code or last 4 digits are required.");
        }
        String normalized = rawReference.trim().toUpperCase(Locale.ROOT);
        if (!CARD_REF_PATTERN.matcher(normalized).matches()) {
            throw new PosRuleException(
                    "VALIDATION_FAILED",
                    422,
                    "Card reference must be an approval code (4 to 12 letters/digits) or the last 4 digits.");
        }
        return new ValidatedPayment(
                PaymentMethod.CARD, billTotal, billTotal, Money.ZERO, normalized);
    }

    /**
     * Checks whether a string looks like a payment card number (PAN).
     *
     * <p>Rejects any input whose digits-after-stripping-spaces-and-dashes are 13 to 19 digits, or
     * any numeric string of 12 or more digits that passes the Luhn check.
     *
     * @param raw the raw reference input
     * @return {@code true} if the input resembles a full card number
     */
    public static boolean looksLikeCardNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String stripped = raw.replaceAll("[\\s-]+", "");
        if (!ALL_DIGITS.matcher(stripped).matches()) {
            return false;
        }
        int len = stripped.length();
        if (len >= 13 && len <= 19) {
            return true;
        }
        return len >= 12 && passesLuhn(stripped);
    }

    /**
     * Evaluates the standard Mod-10 Luhn checksum on an all-digit string.
     *
     * @param digits non-empty digit-only string
     * @return {@code true} if the Luhn check succeeds
     */
    public static boolean passesLuhn(String digits) {
        if (digits == null || digits.isEmpty() || !ALL_DIGITS.matcher(digits).matches()) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}
