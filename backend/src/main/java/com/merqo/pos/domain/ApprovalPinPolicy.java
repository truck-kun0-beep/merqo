package com.merqo.pos.domain;

import java.util.regex.Pattern;

/**
 * Policy validating manager discount-approval PINs.
 *
 * <p>Responsibility: require 4 to 8 numeric digits and, outside demo seeding, reject trivial PINs
 * such as all-identical digits (e.g. {@code 1111}) or straight ascending/descending sequences (e.g.
 * {@code 1234}, {@code 4321}).
 *
 * <p>OOP concept: <b>Single Responsibility &amp; Encapsulation</b> — centralizes PIN strength
 * validation in a pure domain policy independent of hashing or persistence.
 */
public final class ApprovalPinPolicy {

    /** Minimum allowed PIN length. */
    public static final int MIN_LENGTH = 4;

    /** Maximum allowed PIN length. */
    public static final int MAX_LENGTH = 8;

    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d{4,8}$");

    private ApprovalPinPolicy() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Validates a PIN under full production rules.
     *
     * @param pin the raw PIN string
     */
    public static void validate(String pin) {
        validate(pin, false);
    }

    /**
     * Validates a PIN, optionally bypassing weak-sequence checks for demo seeding only.
     *
     * @param pin the raw PIN string
     * @param allowWeakDemoPin {@code true} ONLY during demo seeding (e.g. demo PIN {@code 1234})
     */
    public static void validate(String pin, boolean allowWeakDemoPin) {
        if (pin == null || !DIGITS_ONLY.matcher(pin).matches()) {
            throw new PosRuleException("PIN_POLICY", 422, "Approval PIN must be 4 to 8 digits.");
        }
        if (allowWeakDemoPin) {
            return;
        }
        if (isAllSameDigits(pin)) {
            throw new PosRuleException(
                    "PIN_POLICY", 422, "Approval PIN must not consist of a single repeated digit.");
        }
        if (isStraightSequence(pin)) {
            throw new PosRuleException(
                    "PIN_POLICY",
                    422,
                    "Approval PIN must not be a straight ascending or descending digit sequence.");
        }
    }

    private static boolean isAllSameDigits(String pin) {
        char first = pin.charAt(0);
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStraightSequence(String pin) {
        boolean ascending = true;
        boolean descending = true;
        for (int i = 1; i < pin.length(); i++) {
            int diff = pin.charAt(i) - pin.charAt(i - 1);
            if (diff != 1) {
                ascending = false;
            }
            if (diff != -1) {
                descending = false;
            }
        }
        return ascending || descending;
    }
}
