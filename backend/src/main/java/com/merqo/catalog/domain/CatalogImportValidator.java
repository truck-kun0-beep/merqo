package com.merqo.catalog.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the rows of a catalog import.
 *
 * <p>Responsibility: look at the parsed rows of an import and say what is wrong with each of them.
 *
 * <p>The validator never writes anything. It only produces a list of errors, and every error
 * carries the number of the row it belongs to, so the screen can show "row 14: the barcode is
 * already used".
 *
 * <p>Every rule of the validator is a plain check on one row or on two rows compared with each
 * other, so the class has no Spring in it and is unit tested on its own.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the validator answers "is this file usable?" and nothing
 *       else. The commit service asks it before it writes anything.
 *   <li><b>Pure function</b> - the same file always gives the same errors.
 *   <li><b>Composition</b> - the answer is a {@link Result} that carries the errors and the
 *       summary, instead of two separate lists.
 * </ul>
 */
public final class CatalogImportValidator {

    /** The columns of the import file, in the order they must appear. */
    public static final List<String> COLUMNS =
            List.of(
                    "product_code",
                    "product_name",
                    "category",
                    "brand",
                    "color",
                    "size",
                    "other",
                    "sku",
                    "barcode",
                    "selling_price",
                    "cost",
                    "opening_qty");

    /** The biggest file the import accepts, in rows. */
    public static final int MAX_ROWS = 2000;

    private CatalogImportValidator() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Checks the rows of an import.
     *
     * @param rows the rows of the file, without the header
     * @param knownSkus the SKUs that are already in the catalogue, in lower case
     * @param knownBarcodes the barcodes that are already in the catalogue
     * @return the result, with the errors and the summary
     */
    public static Result validate(
            List<List<String>> rows,
            java.util.Set<String> knownSkus,
            java.util.Set<String> knownBarcodes) {

        List<Error> errors = new ArrayList<>();
        if (rows == null) {
            return new Result(errors, 0, 0, 0);
        }
        if (rows.size() > MAX_ROWS) {
            errors.add(
                    new Error(
                            0,
                            "The file has "
                                    + rows.size()
                                    + " rows, but at most "
                                    + MAX_ROWS
                                    + " rows are accepted."));
            return new Result(errors, 0, 0, 0);
        }

        Map<String, Integer> skuInFile = new LinkedHashMap<>();
        Map<String, Integer> barcodeInFile = new LinkedHashMap<>();
        int toCreate = 0;
        int toUpdate = 0;
        int openingStockLines = 0;

        for (int index = 0; index < rows.size(); index++) {
            int rowNumber = index + 2;
            List<String> row = rows.get(index);
            if (row.size() < COLUMNS.size()) {
                errors.add(
                        new Error(
                                rowNumber,
                                "The row has "
                                        + row.size()
                                        + " columns, but "
                                        + COLUMNS.size()
                                        + " are needed."));
                continue;
            }

            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < COLUMNS.size(); column++) {
                values.put(COLUMNS.get(column), row.get(column));
            }

            String productCode = values.get("product_code").trim();
            String productName = values.get("product_name").trim();
            String sku = values.get("sku").trim();
            String barcode = values.get("barcode").trim();

            if (productCode.isEmpty()) {
                errors.add(new Error(rowNumber, "The product code is empty."));
            }
            if (productName.isEmpty()) {
                errors.add(new Error(rowNumber, "The product name is empty."));
            }
            if (sku.isEmpty()) {
                errors.add(new Error(rowNumber, "The SKU is empty."));
            }

            if (!sku.isEmpty()) {
                Integer earlier = skuInFile.get(sku.toLowerCase(java.util.Locale.ROOT));
                if (earlier != null) {
                    errors.add(
                            new Error(
                                    rowNumber,
                                    "The SKU " + sku + " is already used in row " + earlier + "."));
                } else {
                    skuInFile.put(sku.toLowerCase(java.util.Locale.ROOT), rowNumber);
                }
                if (knownSkus != null
                        && knownSkus.contains(sku.toLowerCase(java.util.Locale.ROOT))) {
                    toUpdate++;
                } else if (earlier == null) {
                    toCreate++;
                }
            }

            if (!barcode.isEmpty()) {
                String reason = BarcodeRules.rejectionReason(barcode);
                if (reason != null) {
                    errors.add(new Error(rowNumber, reason));
                } else {
                    Integer earlier = barcodeInFile.get(barcode);
                    if (earlier != null) {
                        errors.add(
                                new Error(
                                        rowNumber,
                                        "The barcode "
                                                + barcode
                                                + " is already used in row "
                                                + earlier
                                                + "."));
                    } else {
                        barcodeInFile.put(barcode, rowNumber);
                    }
                    if (knownBarcodes != null && knownBarcodes.contains(barcode)) {
                        errors.add(
                                new Error(
                                        rowNumber,
                                        "The barcode "
                                                + barcode
                                                + " is already used by another variant."));
                    }
                }
            }

            String price = values.get("selling_price").trim();
            if (price.isEmpty()) {
                errors.add(new Error(rowNumber, "The selling price is empty."));
            } else {
                java.math.BigDecimal parsed = parseNumber(price);
                if (parsed == null) {
                    errors.add(
                            new Error(
                                    rowNumber, "The selling price " + price + " is not a number."));
                } else if (parsed.signum() < 0) {
                    errors.add(new Error(rowNumber, "The selling price must not be negative."));
                }
            }

            String cost = values.get("cost").trim();
            if (!cost.isEmpty()) {
                java.math.BigDecimal parsed = parseNumber(cost);
                if (parsed == null) {
                    errors.add(new Error(rowNumber, "The cost " + cost + " is not a number."));
                } else if (parsed.signum() < 0) {
                    errors.add(new Error(rowNumber, "The cost must not be negative."));
                }
            }

            String openingQty = values.get("opening_qty").trim();
            if (!openingQty.isEmpty()) {
                java.math.BigDecimal parsed = parseNumber(openingQty);
                if (parsed == null) {
                    errors.add(
                            new Error(
                                    rowNumber,
                                    "The opening quantity " + openingQty + " is not a number."));
                } else if (parsed.stripTrailingZeros().scale() > 0) {
                    errors.add(
                            new Error(rowNumber, "The opening quantity must be a whole number."));
                } else if (parsed.signum() < 0) {
                    errors.add(new Error(rowNumber, "The opening quantity must not be negative."));
                } else {
                    openingStockLines++;
                }
            }
        }

        return new Result(errors, toCreate, toUpdate, openingStockLines);
    }

    private static java.math.BigDecimal parseNumber(String text) {
        try {
            return new java.math.BigDecimal(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * One problem with one row.
     *
     * @param row the number of the row in the file, where the header is row 1
     * @param message what is wrong
     */
    public record Error(int row, String message) {}

    /**
     * The answer of the validator.
     *
     * @param errors the problems, one per row
     * @param toCreate how many rows would create a new product
     * @param toUpdate how many rows would update an existing product, matched by SKU
     * @param openingStockLines how many rows carry an opening quantity
     */
    public record Result(List<Error> errors, int toCreate, int toUpdate, int openingStockLines) {

        /**
         * Canonical constructor. The list of errors is copied.
         *
         * @param errors the problems
         * @param toCreate how many rows would create a new product
         * @param toUpdate how many rows would update an existing product
         * @param openingStockLines how many rows carry an opening quantity
         */
        public Result {
            errors = List.copyOf(errors);
        }

        /**
         * Whether the file may be committed.
         *
         * @return {@code true} when there is no error
         */
        public boolean isValid() {
            return errors.isEmpty();
        }
    }
}
