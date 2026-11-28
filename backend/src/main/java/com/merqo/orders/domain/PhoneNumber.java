package com.merqo.orders.domain;

import java.util.regex.Pattern;

/**
 * Immutable value object representing a Bangladeshi mobile phone number.
 *
 * <p>Responsibility: preserve the raw caller-supplied phone string while normalizing valid numbers
 * into the canonical 11-digit local format {@code 01[3-9]XXXXXXXX}. Invalid numbers retain {@code
 * normalized = null} rather than throwing during normalization, so webhook orders with bad numbers
 * can still be captured for staff correction.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Value object / immutability</b> - normalized upon construction; state never mutates.
 *   <li><b>Encapsulation</b> - all country-code and formatting rules stay in one place.
 * </ul>
 *
 * @param raw original phone input as received
 * @param normalized normalized 11-digit Bangladeshi mobile number, or {@code null} when invalid
 */
public record PhoneNumber(String raw, String normalized) {

    private static final Pattern VALID_BD_MOBILE = Pattern.compile("^01[3-9]\\d{8}$");

    /**
     * Parses and normalizes a Bangladeshi mobile number.
     *
     * @param rawInput caller input (spaces, dashes, parentheses, and +880/880 prefixes allowed)
     * @return immutable {@link PhoneNumber} holding raw input and nullable normalized value
     */
    public static PhoneNumber parse(String rawInput) {
        String raw = rawInput == null ? "" : rawInput.trim();
        if (raw.isEmpty()) {
            return new PhoneNumber("", null);
        }
        String stripped = raw.replaceAll("[\\s\\-()]", "");
        if (stripped.startsWith("+880")) {
            stripped = "0" + stripped.substring(4);
        } else if (stripped.startsWith("880")) {
            stripped = "0" + stripped.substring(3);
        }
        String normalized = VALID_BD_MOBILE.matcher(stripped).matches() ? stripped : null;
        return new PhoneNumber(raw, normalized);
    }

    /**
     * Whether this phone number normalized into a valid Bangladeshi mobile number.
     *
     * @return {@code true} when {@link #normalized()} is non-null
     */
    public boolean isValid() {
        return normalized != null;
    }
}
