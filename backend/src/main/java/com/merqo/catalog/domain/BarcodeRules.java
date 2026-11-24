package com.merqo.catalog.domain;

import java.util.Locale;

/**
 * The rules of a barcode.
 *
 * <p>Responsibility: normalise a barcode and say whether it may be stored.
 *
 * <p>The rules:
 *
 * <ol>
 *   <li>Trim the surrounding whitespace.
 *   <li>At most 64 characters and at least one.
 *   <li>No whitespace and no control characters inside the code.
 *   <li>The match is exact and case sensitive, so the code is stored as it was written.
 *   <li>The prefix {@code MQ-} is reserved for the Merqo document numbers, such as a receipt
 *       number, and may never be used as a barcode.
 * </ol>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the rules live in one class, so the catalog service, the import and
 *       the tests all apply exactly the same ones.
 *   <li><b>Single responsibility</b> - this class only knows about barcodes. It knows nothing about
 *       products, prices or stock.
 *   <li><b>Null Object / value semantics</b> - {@link #isValid(String)} answers {@code false} for
 *       {@code null} instead of throwing, so a caller can filter a list without a try and catch.
 * </ul>
 */
public final class BarcodeRules {

    /** The prefix that is reserved for the Merqo document numbers. */
    public static final String RESERVED_PREFIX = "MQ-";

    /** The biggest barcode Merqo accepts. */
    public static final int MAX_LENGTH = 64;

    private BarcodeRules() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Normalises a barcode: trims it and leaves the case alone.
     *
     * <p>The case is left alone on purpose. The match is case sensitive, so changing the case here
     * would change which variant a scan finds.
     *
     * @param raw the barcode as it was written or scanned
     * @return the trimmed barcode, or an empty string when the input is {@code null}
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    /**
     * Whether a barcode may be stored.
     *
     * @param raw the barcode as it was written or scanned
     * @return {@code true} when the barcode is acceptable
     */
    public static boolean isValid(String raw) {
        String barcode = normalize(raw);
        if (barcode.isEmpty() || barcode.length() > MAX_LENGTH) {
            return false;
        }
        if (barcode.toUpperCase(Locale.ROOT).startsWith(RESERVED_PREFIX)) {
            return false;
        }
        for (int index = 0; index < barcode.length(); index++) {
            char character = barcode.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Why a barcode was refused, in one sentence.
     *
     * @param raw the barcode as it was written or scanned
     * @return the reason, or {@code null} when the barcode is acceptable
     */
    public static String rejectionReason(String raw) {
        String barcode = normalize(raw);
        if (barcode.isEmpty()) {
            return "The barcode is empty.";
        }
        if (barcode.length() > MAX_LENGTH) {
            return "The barcode is longer than " + MAX_LENGTH + " characters.";
        }
        if (barcode.toUpperCase(Locale.ROOT).startsWith(RESERVED_PREFIX)) {
            return "The prefix " + RESERVED_PREFIX + " is reserved for Merqo document numbers.";
        }
        for (int index = 0; index < barcode.length(); index++) {
            char character = barcode.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                return "The barcode may not contain spaces or control characters.";
            }
        }
        return null;
    }
}
