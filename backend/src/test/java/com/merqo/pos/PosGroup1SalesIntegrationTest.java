package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.merqo.access.AccountType;
import com.merqo.common.web.CsrfTestSupport;

/**
 * Group 1 integration tests closing remaining sales &amp; idempotency gaps from {@code
 * docs/PHASE3_SPEC.md} (§8).
 */
class PosGroup1SalesIntegrationTest extends PosIntegrationSupport {

    @Test
    @DisplayName("empty cart on POST /pos/sales returns 422 EMPTY_CART and writes no rows")
    void emptyCartReturnsEmptyCartError() throws Exception {
        UUID dmId = locationId("DM");
        Cookie cashier =
                createAndSignIn(
                        "g1.empty", AccountType.STAFF, "CASHIER", dmId, new BigDecimal("5"));
        UUID sessionId = openSession(cashier, dmId, "1000.00");

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [],
                                          "payment": {"method": "CASH", "tendered": "100.00"}
                                        }
                                        """
                                                .formatted(dmId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("EMPTY_CART"));

        Integer salesInSession =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sales WHERE cash_session_id = ?",
                        Integer.class,
                        sessionId);
        assertEquals(0, salesInSession);
    }

    @Test
    @DisplayName("idempotent replay writes one sale, one movement row, and keeps WH untouched")
    void idempotentReplayWritesSingleSaleAndSingleMovement() throws Exception {
        UUID dmId = locationId("DM");
        UUID whId = locationId("WH");
        ProductFixture fixture = createProductFixture("G1IDM", "400.00", "200.00");
        addOpeningStock(dmId, fixture.variant().getId(), 8);
        addOpeningStock(whId, fixture.variant().getId(), 15);

        Cookie cashier =
                createAndSignIn("g1.idem", AccountType.STAFF, "CASHIER", dmId, new BigDecimal("5"));
        openSession(cashier, dmId, "1000.00");

        String idemKey = "g1-key-" + shortId();
        String payload =
                """
                {
                  "locationId": "%s",
                  "lines": [{"variantId": "%s", "quantity": 3}],
                  "payment": {"method": "CASH", "tendered": "1500.00"}
                }
                """
                        .formatted(dmId, fixture.variant().getId());

        String first =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(cashier)
                                        .with(CsrfTestSupport.csrf())
                                        .header("Idempotency-Key", idemKey)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.total").value("1200.00"))
                        .andExpect(jsonPath("$.changeGiven").value("300.00"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode firstNode = MAPPER.readTree(first);
        UUID saleId = UUID.fromString(firstNode.get("id").asText());

        String replay =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(cashier)
                                        .with(CsrfTestSupport.csrf())
                                        .header("Idempotency-Key", idemKey)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertEquals(saleId.toString(), MAPPER.readTree(replay).get("id").asText());

        Integer movementCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM stock_movements WHERE reference_type = 'SALE' AND reference_id = ?",
                        Integer.class,
                        saleId);
        assertEquals(1, movementCount);
        assertEquals(5, inventoryService.balanceOf(dmId, fixture.variant().getId()).getOnHand());
        assertEquals(15, inventoryService.balanceOf(whId, fixture.variant().getId()).getOnHand());

        // Same key with changed body -> 409 IDEMPOTENCY_CONFLICT
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf())
                                .header("Idempotency-Key", idemKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 4}],
                                          "payment": {"method": "CASH", "tendered": "2000.00"}
                                        }
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
    }
}
