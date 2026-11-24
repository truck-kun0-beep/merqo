package com.merqo.catalog.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The tests of the barcode rules.
 *
 * <p>These tests run without Spring and without a database, because {@link BarcodeRules} is a plain
 * Java class.
 */
class BarcodeRulesTest {

    @Nested
    @DisplayName("the normalisation")
    class Normalization {

        @Test
        @DisplayName("trims the spaces around the code")
        void trimsTheCode() {
            assertEquals("2000000000001", BarcodeRules.normalize("  2000000000001  "));
        }

        @Test
        @DisplayName("leaves the case alone, because the match is case sensitive")
        void leavesTheCaseAlone() {
            assertEquals("AbC123", BarcodeRules.normalize("AbC123"));
        }

        @Test
        @DisplayName("turns a missing code into an empty string")
        void turnsNullIntoAnEmptyString() {
            assertEquals("", BarcodeRules.normalize(null));
        }
    }

    @Nested
    @DisplayName("the acceptance")
    class Acceptance {

        @Test
        @DisplayName("accepts a normal barcode")
        void acceptsANormalBarcode() {
            assertTrue(BarcodeRules.isValid("2000000000001"));
        }

        @Test
        @DisplayName("accepts a barcode that is exactly 64 characters long")
        void acceptsTheLongestBarcode() {
            assertTrue(BarcodeRules.isValid("a".repeat(64)));
        }

        @Test
        @DisplayName("refuses a barcode that is longer than 64 characters")
        void refusesTheLongestTooLongBarcode() {
            assertFalse(BarcodeRules.isValid("a".repeat(65)));
        }

        @Test
        @DisplayName("refuses an empty barcode")
        void refusesAnEmptyBarcode() {
            assertFalse(BarcodeRules.isValid(""));
            assertFalse(BarcodeRules.isValid("   "));
            assertFalse(BarcodeRules.isValid(null));
        }

        @Test
        @DisplayName("refuses a barcode with a space inside it")
        void refusesABarcodeWithASpace() {
            assertFalse(BarcodeRules.isValid("200 0001"));
        }

        @Test
        @DisplayName("refuses a barcode with a tab inside it")
        void refusesABarcodeWithATab() {
            assertFalse(BarcodeRules.isValid("200\t0001"));
        }

        @Test
        @DisplayName("refuses a barcode with a control character inside it")
        void refusesABarcodeWithAControlCharacter() {
            assertFalse(BarcodeRules.isValid("2000001"));
        }

        @Test
        @DisplayName("refuses the reserved prefix of the Merqo document numbers")
        void refusesTheReservedPrefix() {
            assertFalse(BarcodeRules.isValid("MQ-000001"));
            assertFalse(BarcodeRules.isValid("mq-000001"));
            assertFalse(BarcodeRules.isValid(" MQ-000001 "));
        }

        @Test
        @DisplayName("accepts a code that only looks like the reserved prefix")
        void acceptsACodeThatOnlyLooksLikeTheReservedPrefix() {
            assertTrue(BarcodeRules.isValid("MQU-000001"));
            assertTrue(BarcodeRules.isValid("MQX-1"));
        }
    }

    @Nested
    @DisplayName("the reason")
    class Reason {

        @Test
        @DisplayName("is empty for a good barcode")
        void isEmptyForAGoodBarcode() {
            assertNull(BarcodeRules.rejectionReason("2000000000001"));
        }

        @Test
        @DisplayName("names the empty barcode")
        void namesTheEmptyBarcode() {
            assertNotNull(BarcodeRules.rejectionReason(""));
            assertTrue(BarcodeRules.rejectionReason("").contains("empty"));
        }

        @Test
        @DisplayName("names the reserved prefix")
        void namesTheReservedPrefix() {
            assertTrue(BarcodeRules.rejectionReason("MQ-1").contains("reserved"));
        }

        @Test
        @DisplayName("names the space inside the code")
        void namesTheSpaceInsideTheCode() {
            assertTrue(BarcodeRules.rejectionReason("2 0").contains("spaces"));
        }
    }
}
