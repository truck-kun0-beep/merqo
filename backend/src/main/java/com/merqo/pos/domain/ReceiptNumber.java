package com.merqo.pos.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import com.merqo.common.time.BusinessTime;

/**
 * Formats and normalizes Merqo POS receipt numbers.
 *
 * <p>Responsibility: format {@code MQ-<year>-<6 digits>} using the calendar year in {@code
 * Asia/Dhaka} (never UTC, so a sale at 18:00 UTC on 31 Dec gets the new Dhaka year), and normalize
 * receipt lookup inputs to upper case.
 *
 * <p>Collision guarantee: {@link com.merqo.catalog.domain.BarcodeRules} reserves the {@code MQ-}
 * prefix at both Java and database constraint levels, so a receipt number can never collide with a
 * product barcode.
 *
 * <p>OOP concept: <b>Single Responsibility &amp; Domain Primitives</b> — concentrates receipt
 * number formatting and normalization rules in one place.
 */
public final class ReceiptNumber {

    private static final Pattern FORMAT = Pattern.compile("^MQ-\\d{4}-\\d{6,}$");

    private ReceiptNumber() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Formats a receipt number using the Asia/Dhaka year of {@code issuedAt} and a positive
     * sequence number zero-padded to at least 6 digits.
     *
     * @param issuedAt UTC instant when the sale occurred
     * @param sequenceValue positive sequence number from {@code receipt_number_sequence}
     * @return formatted receipt number such as {@code MQ-2026-000001}
     */
    public static String format(Instant issuedAt, long sequenceValue) {
        if (issuedAt == null) {
            throw new IllegalArgumentException("Issued timestamp must not be null.");
        }
        if (sequenceValue <= 0) {
            throw new IllegalArgumentException("Sequence value must be positive.");
        }
        int dhakaYear = issuedAt.atZone(BusinessTime.BUSINESS_ZONE).getYear();
        return "MQ-%04d-%06d".formatted(dhakaYear, sequenceValue);
    }

    /**
     * Normalizes a receipt number entered by a cashier for lookup (trimmed, upper case).
     *
     * @param raw the raw receipt number input
     * @return normalized receipt number, or empty string if {@code null}
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Checks whether a normalized receipt number matches the expected {@code MQ-YYYY-NNNNNN}
     * pattern.
     *
     * @param receiptNumber receipt number to check
     * @return {@code true} if well-formed
     */
    public static boolean isValidFormat(String receiptNumber) {
        if (receiptNumber == null) {
            return false;
        }
        return FORMAT.matcher(normalize(receiptNumber)).matches();
    }
}
