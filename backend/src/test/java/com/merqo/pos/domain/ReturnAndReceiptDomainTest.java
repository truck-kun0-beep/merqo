package com.merqo.pos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.merqo.catalog.domain.BarcodeRules;
import com.merqo.common.money.Money;

/**
 * Pure Java unit tests for {@link ReturnRules}, {@link ReceiptNumber}, {@link JsonReceiptRenderer},
 * and {@link PlainTextReceiptRenderer} (no Spring).
 */
class ReturnAndReceiptDomainTest {

    @Nested
    @DisplayName("ReturnRules")
    class ReturnRulesTests {

        @Test
        @DisplayName(
                "full return after partial returns refunds exact remaining total without rounding drift")
        void zeroRoundingDriftAcrossPartialReturns() {
            Money paid = Money.of(100);
            Money r1 = ReturnRules.computeLineRefund(3, 0, 1, paid, Money.ZERO);
            assertEquals(Money.parse("33.33"), r1);

            Money r2 = ReturnRules.computeLineRefund(3, 1, 1, paid, r1);
            assertEquals(Money.parse("33.33"), r2);

            Money r3 = ReturnRules.computeLineRefund(3, 2, 1, paid, r1.plus(r2));
            assertEquals(Money.parse("33.34"), r3);
            assertEquals(paid, r1.plus(r2).plus(r3));
        }

        @Test
        @DisplayName("rejects zero, negative, over-quantity, and already-returned lines")
        void rejectsInvalidReturnQuantities() {
            PosRuleException zero =
                    assertThrows(
                            PosRuleException.class,
                            () ->
                                    ReturnRules.computeLineRefund(
                                            2, 0, 0, Money.of(200), Money.ZERO));
            assertEquals("VALIDATION_FAILED", zero.getErrorCode());

            PosRuleException over =
                    assertThrows(
                            PosRuleException.class,
                            () ->
                                    ReturnRules.computeLineRefund(
                                            2, 1, 2, Money.of(200), Money.of(100)));
            assertEquals("RETURN_QUANTITY_EXCEEDED", over.getErrorCode());

            PosRuleException done =
                    assertThrows(
                            PosRuleException.class,
                            () ->
                                    ReturnRules.computeLineRefund(
                                            2, 2, 1, Money.of(200), Money.of(200)));
            assertEquals("ALREADY_RETURNED", done.getErrorCode());
        }
    }

    @Nested
    @DisplayName("ReceiptNumber")
    class ReceiptNumberTests {

        @Test
        @DisplayName("uses Asia/Dhaka year boundary (17:59:59 UTC vs 18:00:00 UTC on 31 Dec)")
        void dhakaYearBoundary() {
            Instant beforeMidnightDhaka = Instant.parse("2026-12-31T17:59:59Z");
            Instant atMidnightDhaka = Instant.parse("2026-12-31T18:00:00Z");

            String dec31 = ReceiptNumber.format(beforeMidnightDhaka, 1);
            String jan1 = ReceiptNumber.format(atMidnightDhaka, 2);

            assertEquals("MQ-2026-000001", dec31);
            assertEquals("MQ-2027-000002", jan1);
            assertFalse(BarcodeRules.isValid(dec31));
        }

        @Test
        @DisplayName("normalizes receipt number lookup to uppercase")
        void normalizesLookup() {
            assertEquals("MQ-2026-000042", ReceiptNumber.normalize("  mq-2026-000042 "));
            assertTrue(ReceiptNumber.isValidFormat("mq-2026-000042"));
        }
    }

    @Nested
    @DisplayName("ReceiptRenderers")
    class RendererTests {

        private ReceiptData sample(boolean duplicate) {
            return new ReceiptData(
                    "Merqo Demo Store",
                    "Dhanmondi",
                    "House 12, Road 4, Dhanmondi",
                    "01700000000",
                    "MQ-2026-000001",
                    Instant.parse("2026-11-27T10:15:00Z"),
                    "Demo Cashier",
                    List.of(
                            new ReceiptLineItem(
                                    "Cotton Crew T-Shirt",
                                    "Black / M",
                                    2,
                                    Money.of(750),
                                    Money.of(150),
                                    Money.of(1350))),
                    Money.of(1350),
                    Money.of(150),
                    Money.ZERO,
                    Money.of(1350),
                    PaymentMethod.BKASH,
                    "TRX998877",
                    "Thank you for shopping with us.",
                    duplicate);
        }

        @Test
        @DisplayName(
                "JsonReceiptRenderer formats exact contract keys and 2-decimal money strings without cost keys")
        void jsonRendererMatchesContract() {
            Map<String, Object> json = new JsonReceiptRenderer().render(sample(false));

            assertEquals("MQ-2026-000001", json.get("receiptNumber"));
            assertEquals("1350.00", json.get("subtotal"));
            assertEquals("150.00", json.get("discount"));
            assertEquals("0.00", json.get("tax"));
            assertEquals("1350.00", json.get("total"));
            assertEquals("BKASH", json.get("paymentMethod"));
            assertEquals("TRX998877", json.get("paymentReference"));
            assertEquals(false, json.get("isDuplicate"));
            assertFalse(json.containsKey("costPrice"));
            assertFalse(json.containsKey("unitCost"));
        }

        @Test
        @DisplayName(
                "PlainTextReceiptRenderer respects 42-col (80mm) and 32-col (58mm) widths and duplicate banner")
        void plainTextRendererWidthsAndDuplicate() {
            String text80 = new PlainTextReceiptRenderer(80).render(sample(true));
            assertTrue(text80.contains("*** DUPLICATE ***"));
            assertTrue(text80.contains("MQ-2026-000001"));
            for (String line : text80.split("\n")) {
                assertTrue(line.length() <= 42, "80mm line exceeded 42 chars: " + line);
            }

            String text58 = new PlainTextReceiptRenderer(58).render(sample(false));
            assertFalse(text58.contains("*** DUPLICATE ***"));
            for (String line : text58.split("\n")) {
                assertTrue(line.length() <= 32, "58mm line exceeded 32 chars: " + line);
            }
        }
    }
}
