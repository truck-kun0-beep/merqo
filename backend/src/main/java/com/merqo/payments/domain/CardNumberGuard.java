package com.merqo.payments.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.merqo.pos.domain.PaymentRules;

/**
 * Pure domain guard preventing full payment card numbers (PANs) from entering references or free
 * text fields.
 *
 * <p>Responsibility: reuse Phase 3's {@link PaymentRules#looksLikeCardNumber(String)} check on
 * exact references and embedded digit sequences inside notes or verification evidence.
 *
 * <p>OOP concept: <b>Reuse &amp; Single Responsibility</b> — centralizes PAN detection across every
 * online payment, refund, and COD input field.
 */
public final class CardNumberGuard {

    private static final Pattern DIGIT_RUN_PATTERN = Pattern.compile("[0-9][0-9\\s-]{10,}[0-9]");

    private CardNumberGuard() {}

    /**
     * Rejects a reference value that resembles a payment card number.
     *
     * @param rawReference raw payment reference
     */
    public static void rejectCardNumberReference(String rawReference) {
        if (PaymentRules.looksLikeCardNumber(rawReference)) {
            throw new PaymentRuleException(
                    "CARD_NUMBER_REJECTED",
                    422,
                    "Full card numbers are never accepted. Enter only the last 4 digits or approval code.");
        }
    }

    /**
     * Rejects free text (notes, evidence, remarks) if it contains any card-number-like sequence.
     *
     * @param text raw free text (nullable)
     */
    public static void rejectCardNumberInText(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (PaymentRules.looksLikeCardNumber(text)) {
            throw new PaymentRuleException(
                    "CARD_NUMBER_REJECTED",
                    422,
                    "Full card numbers are never accepted in notes or evidence.");
        }
        Matcher matcher = DIGIT_RUN_PATTERN.matcher(text);
        while (matcher.find()) {
            if (PaymentRules.looksLikeCardNumber(matcher.group())) {
                throw new PaymentRuleException(
                        "CARD_NUMBER_REJECTED",
                        422,
                        "Full card numbers are never accepted in notes or evidence.");
            }
        }
    }
}
