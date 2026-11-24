package com.merqo.catalog.domain;

import java.util.Locale;
import java.util.Map;

/**
 * Suggests a SKU for a variant that does not have one yet.
 *
 * <p>Responsibility: build a readable SKU from the product code and the attributes of a variant.
 *
 * <p>The suggestion looks like {@code OT001-BLK-M}: the product code, then the colour, then the
 * size, then the other attribute. Every part is optional; the parts that are missing are simply
 * left out.
 *
 * <p>How a part becomes short:
 *
 * <ul>
 *   <li>The common colours and sizes have a short form in the table below, because {@code BLK} on a
 *       shelf label is easier to read than {@code BLA}.
 *   <li>Every other part is cut to its first three letters.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - this class only builds a suggestion. It never checks whether
 *       the SKU is free and it never writes anything.
 *   <li><b>Pure function</b> - the same input always gives the same answer, so the rule can be unit
 *       tested without Spring and without a database.
 *   <li><b>Null Object</b> - a missing part is treated as an empty part instead of a null
 *       reference.
 *   <li><b>Strategy</b> - the short forms live in a table, so a new colour only needs one line.
 * </ul>
 */
public final class SkuSuggester {

    /** How many letters of an attribute are used when there is no short form. */
    public static final int PART_LENGTH = 3;

    /** The character that joins the parts. */
    public static final String SEPARATOR = "-";

    /**
     * The short forms of the common colours and sizes.
     *
     * <p>The keys are in upper case, because the short form is the same whatever the case of the
     * attribute was.
     */
    private static final Map<String, String> SHORT_FORMS =
            Map.ofEntries(
                    Map.entry("BLACK", "BLK"),
                    Map.entry("WHITE", "WHT"),
                    Map.entry("BLUE", "BLU"),
                    Map.entry("GREEN", "GRN"),
                    Map.entry("GREY", "GRY"),
                    Map.entry("GRAY", "GRY"),
                    Map.entry("RED", "RED"),
                    Map.entry("YELLOW", "YEL"),
                    Map.entry("ORANGE", "ORG"),
                    Map.entry("BROWN", "BRN"),
                    Map.entry("BEIGE", "BEG"),
                    Map.entry("NAVY", "NVY"),
                    Map.entry("MAROON", "MRN"),
                    Map.entry("PINK", "PNK"),
                    Map.entry("PURPLE", "PPL"),
                    Map.entry("EXTRA SMALL", "XS"),
                    Map.entry("SMALL", "S"),
                    Map.entry("MEDIUM", "M"),
                    Map.entry("LARGE", "L"),
                    Map.entry("EXTRA LARGE", "XL"),
                    Map.entry("ONE SIZE", "OS"));

    private SkuSuggester() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Suggests a SKU.
     *
     * @param productCode the code of the product
     * @param color the colour of the variant, may be {@code null}
     * @param size the size of the variant, may be {@code null}
     * @param otherAttribute the other attribute of the variant, may be {@code null}
     * @return the suggested SKU, in upper case
     */
    public static String suggest(
            String productCode, String color, String size, String otherAttribute) {
        StringBuilder sku = new StringBuilder();
        // The product code is the first part and is used as it is, because it is the code the
        // business already knows.
        String code = productCode == null ? "" : productCode.trim().toUpperCase(Locale.ROOT);
        if (!code.isEmpty()) {
            sku.append(code);
        }
        appendPart(sku, color);
        appendPart(sku, size);
        appendPart(sku, otherAttribute);
        return sku.toString();
    }

    private static void appendPart(StringBuilder sku, String part) {
        if (part == null) {
            return;
        }
        String shortened = shorten(part);
        if (shortened.isEmpty()) {
            return;
        }
        if (!sku.isEmpty()) {
            sku.append(SEPARATOR);
        }
        sku.append(shortened);
    }

    /**
     * Shortens one part of the SKU.
     *
     * <p>A part that has a short form in the table uses it. Every other part is cut to three
     * letters.
     *
     * @param part the part
     * @return the shortened part, in upper case
     */
    static String shorten(String part) {
        String trimmed = part.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        String shortForm = SHORT_FORMS.get(upper);
        if (shortForm != null) {
            return shortForm;
        }
        return upper.length() <= PART_LENGTH ? upper : upper.substring(0, PART_LENGTH);
    }
}
