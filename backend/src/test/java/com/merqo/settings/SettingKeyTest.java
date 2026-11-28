package com.merqo.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unit test of the setting keys.
 *
 * <p>It runs without Spring. Every setting validates its own value, so the rules are checked for
 * every key and not only for the ones that happen to be used today.
 */
class SettingKeyTest {

    @Test
    @DisplayName("every key has a name, a type and a default")
    void everyKeyHasANameATypeAndADefault() {
        for (SettingKey key : SettingKey.all()) {
            assertFalse(key.getKey().isBlank(), "A key needs a name");
            assertTrue(key.getKey().contains("."), "A key needs a group prefix: " + key.getKey());
            assertEquals(key, SettingKey.fromKey(key.getKey()));
        }
    }

    @Test
    @DisplayName("the default of every key can be read")
    void theDefaultOfEveryKeyCanBeRead() {
        for (SettingKey key : SettingKey.all()) {
            assertEquals(key.getDefault(), key.parse(key.getDefaultValue()));
        }
    }

    @Test
    @DisplayName("a text setting accepts any text")
    void aTextSettingAcceptsAnyText() {
        assertEquals("Merqo Demo Store", SettingKey.BUSINESS_NAME.parse("Merqo Demo Store"));
        assertEquals("", SettingKey.BUSINESS_ADDRESS.parse(""));
    }

    @Test
    @DisplayName("a boolean setting accepts only true and false")
    void aBooleanSettingAcceptsOnlyTrueAndFalse() {
        assertEquals(Boolean.TRUE, SettingKey.TAX_INCLUSIVE.parse("true"));
        assertEquals(Boolean.FALSE, SettingKey.TAX_INCLUSIVE.parse("FALSE"));
        assertThrows(IllegalArgumentException.class, () -> SettingKey.TAX_INCLUSIVE.parse("yes"));
    }

    @Test
    @DisplayName("an integer setting accepts only whole numbers")
    void anIntegerSettingAcceptsOnlyWholeNumbers() {
        assertEquals(60, SettingKey.THRESHOLD_DEAD_STOCK_DAYS.parse("60"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKey.THRESHOLD_DEAD_STOCK_DAYS.parse("60.5"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKey.THRESHOLD_DEAD_STOCK_DAYS.parse("-1"));
        assertEquals(300, SettingKey.INTEGRATION_SIGNATURE_WINDOW_SECONDS.parse("300"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKey.INTEGRATION_SIGNATURE_WINDOW_SECONDS.parse("59"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKey.INTEGRATION_SIGNATURE_WINDOW_SECONDS.parse("901"));
    }

    @Test
    @DisplayName("a decimal setting accepts a number")
    void aDecimalSettingAcceptsANumber() {
        assertEquals(new BigDecimal("5"), SettingKey.THRESHOLD_MARGIN_DROP_POINTS.parse("5"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKey.THRESHOLD_MARGIN_DROP_POINTS.parse("not a number"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKey.THRESHOLD_MARGIN_DROP_POINTS.parse("-1"));
    }

    @Test
    @DisplayName("an amount accepts at most two decimal places")
    void anAmountAcceptsAtMostTwoDecimalPlaces() {
        assertEquals(
                new BigDecimal("5000.00"),
                SettingKey.THRESHOLD_UNUSUAL_ADJUSTMENT_VALUE.parse("5000.00"));
        assertEquals(
                new BigDecimal("5000.00"),
                SettingKey.THRESHOLD_UNUSUAL_ADJUSTMENT_VALUE.parse("5000"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKey.THRESHOLD_UNUSUAL_ADJUSTMENT_VALUE.parse("5000.005"));
    }

    @Test
    @DisplayName("the paper width accepts only 58 and 80")
    void thePaperWidthAcceptsOnly58And80() {
        assertEquals(80, SettingKey.RECEIPT_PAPER_WIDTH_MM.parse("80"));
        assertEquals(58, SettingKey.RECEIPT_PAPER_WIDTH_MM.parse("58"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKey.RECEIPT_PAPER_WIDTH_MM.parse("70"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SettingKey.RECEIPT_PAPER_WIDTH_MM.parse("a4"));
    }

    @Test
    @DisplayName("an unknown key is not found")
    void anUnknownKeyIsNotFound() {
        assertEquals(null, SettingKey.fromKey("no.such.setting"));
        assertEquals(null, SettingKey.fromKey(null));
    }

    @Test
    @DisplayName("every key belongs to exactly one group")
    void everyKeyBelongsToExactlyOneGroup() {
        int total = 0;
        for (SettingKey.Group group : SettingKey.Group.values()) {
            total += SettingKey.inGroup(group).size();
        }

        assertEquals(SettingKey.all().size(), total);
    }

    @Test
    @DisplayName("the label of a key reads as words")
    void theLabelOfAKeyReadsAsWords() {
        assertEquals("Name", SettingKey.BUSINESS_NAME.getLabel());
        assertEquals("Paper Width Mm", SettingKey.RECEIPT_PAPER_WIDTH_MM.getLabel());
    }

    @Test
    @DisplayName("the session timeouts are read only")
    void theSessionTimeoutsAreReadOnly() {
        assertTrue(SettingKey.readOnlyKeys().contains("session.idle_timeout_minutes"));
        assertTrue(SettingKey.readOnlyKeys().contains("session.absolute_timeout_minutes"));
        assertFalse(SettingKey.readOnlyKeys().contains("business.name"));
    }
}
