package com.merqo.catalog.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The tests of the CSV reader and the import validator.
 *
 * <p>These tests run without Spring and without a database.
 */
class CsvImportTest {

    @Nested
    @DisplayName("the reader")
    class Reader {

        @Test
        @DisplayName("reads the plain rows")
        void readsPlainRows() {
            List<List<String>> rows = CsvReader.read("a,b,c\n1,2,3\n");

            assertEquals(2, rows.size());
            assertEquals(List.of("a", "b", "c"), rows.get(0));
            assertEquals(List.of("1", "2", "3"), rows.get(1));
        }

        @Test
        @DisplayName("keeps a comma that is inside quotes")
        void keepsACommaInsideQuotes() {
            List<List<String>> rows = CsvReader.read("\"a,b\",c\n");

            assertEquals(List.of("a,b", "c"), rows.get(0));
        }

        @Test
        @DisplayName("keeps a line break that is inside quotes")
        void keepsALineBreakInsideQuotes() {
            List<List<String>> rows = CsvReader.read("\"first\nsecond\",c\n");

            assertEquals(1, rows.size());
            assertEquals("first\nsecond", rows.get(0).get(0));
        }

        @Test
        @DisplayName("turns two double quotes inside a quoted cell into one")
        void turnsTwoQuotesIntoOne() {
            List<List<String>> rows = CsvReader.read("\"say \"\"hi\"\"\",c\n");

            assertEquals("say \"hi\"", rows.get(0).get(0));
        }

        @Test
        @DisplayName("drops the byte order mark of a UTF-8 file")
        void dropsTheByteOrderMark() {
            List<List<String>> rows = CsvReader.read("﻿product_code,name\nOT001,Shirt\n");

            assertEquals("product_code", rows.get(0).get(0));
        }

        @Test
        @DisplayName("skips the empty lines")
        void skipsTheEmptyLines() {
            List<List<String>> rows = CsvReader.read("a,b\n\n\nc,d\n");

            assertEquals(2, rows.size());
        }

        @Test
        @DisplayName("reads the last row even without a line break")
        void readsTheLastRowWithoutALineBreak() {
            List<List<String>> rows = CsvReader.read("a,b\nc,d");

            assertEquals(2, rows.size());
            assertEquals(List.of("c", "d"), rows.get(1));
        }

        @Test
        @DisplayName("reads the windows line breaks")
        void readsWindowsLineBreaks() {
            List<List<String>> rows = CsvReader.read("a,b\r\nc,d\r\n");

            assertEquals(2, rows.size());
        }
    }

    @Nested
    @DisplayName("the validator")
    class Validator {

        private static final List<String> GOOD_ROW =
                List.of(
                        "OT001",
                        "T-shirt",
                        "T-Shirts",
                        "Acme",
                        "Black",
                        "Medium",
                        "",
                        "OT001-BLK-M",
                        "2000000000001",
                        "1250.00",
                        "600.00",
                        "10");

        @Test
        @DisplayName("accepts a good file")
        void acceptsAGoodFile() {
            CatalogImportValidator.Result result =
                    CatalogImportValidator.validate(
                            List.of(GOOD_ROW), java.util.Set.of(), java.util.Set.of());

            assertTrue(result.isValid(), result.errors().toString());
            assertEquals(1, result.toCreate());
            assertEquals(1, result.openingStockLines());
        }

        @Test
        @DisplayName("counts a row that updates an existing SKU as an update")
        void countsAnUpdate() {
            CatalogImportValidator.Result result =
                    CatalogImportValidator.validate(
                            List.of(GOOD_ROW), java.util.Set.of("ot001-blk-m"), java.util.Set.of());

            assertTrue(result.isValid(), result.errors().toString());
            assertEquals(0, result.toCreate());
            assertEquals(1, result.toUpdate());
        }

        @Test
        @DisplayName("names the row of an empty product code")
        void namesTheRowOfAnEmptyProductCode() {
            List<String> row = new java.util.ArrayList<>(GOOD_ROW);
            row.set(0, "");

            CatalogImportValidator.Result result =
                    CatalogImportValidator.validate(
                            List.of(row), java.util.Set.of(), java.util.Set.of());

            assertFalse(result.isValid());
            assertEquals(2, result.errors().get(0).row());
            assertTrue(result.errors().get(0).message().contains("product code"));
        }

        @Test
        @DisplayName("names the row of a barcode that is already used")
        void namesTheRowOfAUsedBarcode() {
            CatalogImportValidator.Result result =
                    CatalogImportValidator.validate(
                            List.of(GOOD_ROW),
                            java.util.Set.of(),
                            java.util.Set.of("2000000000001"));

            assertFalse(result.isValid());
            assertEquals(2, result.errors().get(0).row());
            assertTrue(result.errors().get(0).message().contains("barcode"));
        }

        @Test
        @DisplayName("names the row of a selling price that is not a number")
        void namesTheRowOfABadPrice() {
            List<String> row = new java.util.ArrayList<>(GOOD_ROW);
            row.set(9, "free");

            CatalogImportValidator.Result result =
                    CatalogImportValidator.validate(
                            List.of(row), java.util.Set.of(), java.util.Set.of());

            assertFalse(result.isValid());
            assertTrue(result.errors().get(0).message().contains("selling price"));
        }

        @Test
        @DisplayName("names the row of an opening quantity that is not a whole number")
        void namesTheRowOfAFractionalQuantity() {
            List<String> row = new java.util.ArrayList<>(GOOD_ROW);
            row.set(11, "2.5");

            CatalogImportValidator.Result result =
                    CatalogImportValidator.validate(
                            List.of(row), java.util.Set.of(), java.util.Set.of());

            assertFalse(result.isValid());
            assertTrue(result.errors().get(0).message().contains("whole number"));
        }

        @Test
        @DisplayName("refuses a file that is too long")
        void refusesATooLongFile() {
            List<List<String>> rows = new java.util.ArrayList<>();
            for (int index = 0; index < CatalogImportValidator.MAX_ROWS + 1; index++) {
                rows.add(GOOD_ROW);
            }

            CatalogImportValidator.Result result =
                    CatalogImportValidator.validate(rows, java.util.Set.of(), java.util.Set.of());

            assertFalse(result.isValid());
            assertTrue(result.errors().get(0).message().contains("2000"));
        }
    }
}
