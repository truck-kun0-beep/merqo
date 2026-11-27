package com.merqo.pos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.merqo.access.AccountType;
import com.merqo.common.web.CsrfTestSupport;

/**
 * Group 2 integration tests covering payment rules: TrxID requirements, cross-branch bKash &amp;
 * Nagad duplicate TrxID, card PAN rejection across all four methods, and cash tender/change checks.
 */
class PosGroup2PaymentsIntegrationTest extends PosIntegrationSupport {

    @Test
    @DisplayName(
            "requires TrxID for bKash/Nagad, enforces cross-branch uniqueness, and validates cash tender")
    void trxIdRequirementsDuplicatesAndCashTender() throws Exception {
        UUID dmId = locationId("DM");
        UUID utId = locationId("UT");
        ProductFixture fixture = createProductFixture("G2PAY", "500.00", "250.00");
        addOpeningStock(dmId, fixture.variant().getId(), 20);
        addOpeningStock(utId, fixture.variant().getId(), 20);

        Cookie dmCashier =
                createAndSignIn("g2.dm", AccountType.STAFF, "CASHIER", dmId, new BigDecimal("5"));
        Cookie utCashier =
                createAndSignIn("g2.ut", AccountType.STAFF, "CASHIER", utId, new BigDecimal("5"));
        openSession(dmCashier, dmId, "1000.00");
        openSession(utCashier, utId, "1000.00");

        // bKash, Nagad, and Card require a non-blank reference
        for (String method : List.of("BKASH", "NAGAD", "CARD")) {
            mockMvc.perform(
                            post("/api/v1/pos/sales")
                                    .cookie(dmCashier)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {
                                              "locationId": "%s",
                                              "lines": [{"variantId": "%s", "quantity": 1}],
                                              "payment": {"method": "%s", "reference": "  "}
                                            }
                                            """
                                                    .formatted(
                                                            dmId,
                                                            fixture.variant().getId(),
                                                            method)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorCode").value("PAYMENT_REFERENCE_REQUIRED"));
        }

        // Nagad and bKash succeed once at DM and are rejected globally at UT even with different
        // case
        String bkashTrx = "bka" + shortId();
        String nagadTrx = "ngd" + shortId();

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "BKASH", "reference": "%s"}
                                        }
                                        """
                                                .formatted(
                                                        dmId, fixture.variant().getId(), bkashTrx)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.reference").value(bkashTrx.toUpperCase()));

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(utCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "BKASH", "reference": "%s"}
                                        }
                                        """
                                                .formatted(
                                                        utId,
                                                        fixture.variant().getId(),
                                                        bkashTrx.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_REFERENCE"));

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "NAGAD", "reference": "%s"}
                                        }
                                        """
                                                .formatted(
                                                        dmId, fixture.variant().getId(), nagadTrx)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.reference").value(nagadTrx.toUpperCase()));

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(utCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "NAGAD", "reference": "%s"}
                                        }
                                        """
                                                .formatted(
                                                        utId,
                                                        fixture.variant().getId(),
                                                        nagadTrx.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_REFERENCE"));

        // Card approval code passes
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "CARD", "reference": "AUTH4242"}
                                        }
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.reference").value("AUTH4242"));

        // Cash tendered below total -> 422 PAYMENT_INSUFFICIENT; sufficient cash returns exact
        // change
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "CASH", "tendered": "499.99"}
                                        }
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_INSUFFICIENT"));

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "CASH", "tendered": "1000.00"}
                                        }
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.changeGiven").value("500.00"));
    }

    @Test
    @DisplayName(
            "rejects 13, 16, 19 digit card numbers with spaces/dashes and Luhn PANs across every method")
    void rejectsCardNumbersAcrossAllMethodsViaHttp() throws Exception {
        UUID dmId = locationId("DM");
        ProductFixture fixture = createProductFixture("G2PAN", "300.00", "150.00");
        addOpeningStock(dmId, fixture.variant().getId(), 10);

        Cookie cashier =
                createAndSignIn("g2.pan", AccountType.STAFF, "CASHIER", dmId, new BigDecimal("5"));
        openSession(cashier, dmId, "500.00");

        List<String> cardLikeValues =
                List.of(
                        "4111111111111", // 13 digits
                        "4111111111111111", // 16 digits
                        "4111111111111111111", // 19 digits
                        "4111 1111 1111 1111", // 16 digits with spaces
                        "4111-1111-1111-1111", // 16 digits with dashes
                        "411111111117"); // 12-digit Luhn-valid

        for (String method : List.of("CASH", "BKASH", "NAGAD", "CARD")) {
            for (String cardNumber : cardLikeValues) {
                String tenderedField = method.equals("CASH") ? "\"tendered\": \"300.00\"," : "";
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(cashier)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "locationId": "%s",
                                                  "lines": [{"variantId": "%s", "quantity": 1}],
                                                  "payment": {"method": "%s", %s "reference": "%s"}
                                                }
                                                """
                                                        .formatted(
                                                                dmId,
                                                                fixture.variant().getId(),
                                                                method,
                                                                tenderedField,
                                                                cardNumber)))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.errorCode").value("CARD_NUMBER_REJECTED"));
            }
        }
    }
}
