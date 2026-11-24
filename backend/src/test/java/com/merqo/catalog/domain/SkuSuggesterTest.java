package com.merqo.catalog.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tests of the SKU suggester.
 *
 * <p>These tests run without Spring and without a database.
 */
class SkuSuggesterTest {

    @Test
    @DisplayName("builds a SKU from the product code and the attributes")
    void buildsASkuFromTheParts() {
        assertEquals("OT001-BLK-M", SkuSuggester.suggest("OT001", "Black", "Medium", null));
    }

    @Test
    @DisplayName("uses the parts that are there and leaves the others out")
    void usesThePartsThatAreThere() {
        assertEquals("OT001-BLK", SkuSuggester.suggest("OT001", "Black", null, null));
        assertEquals("OT001", SkuSuggester.suggest("OT001", null, null, null));
    }

    @Test
    @DisplayName("shortens a long part to three letters")
    void shortensALongPart() {
        assertEquals("OT001-BLU-M", SkuSuggester.suggest("OT001", "Blue", "Medium", null));
    }

    @Test
    @DisplayName("puts the parts in upper case")
    void putsThePartsInUpperCase() {
        assertEquals("OT001-BLK-M", SkuSuggester.suggest("ot001", "blk", "m", null));
    }

    @Test
    @DisplayName("uses all four parts when they are all there")
    void usesAllFourParts() {
        assertEquals("OT001-BLK-M-LON", SkuSuggester.suggest("OT001", "Black", "Medium", "Long"));
    }
}
