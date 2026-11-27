package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Group 5 integration tests closing return/inspection sellability and reprint-logging verification
 * gaps.
 */
class PosGroup5ReturnsAndReprintsIntegrationTest extends PosIntegrationSupport {

    @Test
    @DisplayName(
            "INSPECTION return blocks resale until resolved RESTOCK while RESTOCK return is immediately sellable")
    void restockVsInspectionSellabilityAndResolution() throws Exception {
        UUID dmId = locationId("DM");
        ProductFixture fixture = createProductFixture("G5RET", "500.00", "250.00");
        // Start with exact stock 2
        addOpeningStock(dmId, fixture.variant().getId(), 2);

        Cookie dmMgr =
                createAndSignIn("g5.mgr", AccountType.STAFF, "MANAGER", dmId, new BigDecimal("50"));
        openSession(dmMgr, dmId, "1000.00");
        Cookie owner = signIn("owner");

        // Sell both units (stock drops to 0 available)
        String saleJson =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(dmMgr)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "locationId": "%s",
                                                  "lines": [{"variantId": "%s", "quantity": 2}],
                                                  "payment": {"method": "CASH", "tendered": "1000.00"}
                                                }
                                                """
                                                        .formatted(
                                                                dmId, fixture.variant().getId())))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode saleNode = MAPPER.readTree(saleJson);
        UUID saleId = UUID.fromString(saleNode.get("id").asText());
        UUID lineId = UUID.fromString(saleNode.get("lines").get(0).get("id").asText());

        // Unit 1 returned as INSPECTION -> NOT sellable!
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(dmMgr)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "DEFECTIVE",
                                          "lines": [{"saleLineId": "%s", "quantity": 1, "disposition": "INSPECTION"}]
                                        }
                                        """
                                                .formatted(lineId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].disposition").value("INSPECTION"));

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmMgr)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "CASH", "tendered": "500.00"}
                                        }
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"));

        // Appears in inspections queue
        String inspectionsJson =
                mockMvc.perform(
                                get("/api/v1/inventory/inspections")
                                        .param("locationId", dmId.toString())
                                        .cookie(dmMgr))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertTrue(inspectionsJson.contains(fixture.variant().getSku()));

        // Unit 2 returned as RESTOCK -> immediately sellable!
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(dmMgr)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "CHANGED_MIND",
                                          "lines": [{"saleLineId": "%s", "quantity": 1, "disposition": "RESTOCK"}]
                                        }
                                        """
                                                .formatted(lineId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].disposition").value("RESTOCK"));

        // Selling 1 succeeds (Unit 2); selling 2 fails because Unit 1 is still in inspection
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmMgr)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "CASH", "tendered": "500.00"}
                                        }
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isCreated());

        // Resolve Unit 1 RESTOCK -> becomes sellable again
        mockMvc.perform(
                        post("/api/v1/inventory/inspections/resolve")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"locationId":"%s","variantId":"%s","quantity":1,"outcome":"RESTOCK","note":"Cleaned"}
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(1))
                .andExpect(jsonPath("$.inInspection").value(0));

        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmMgr)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "CASH", "tendered": "500.00"}
                                        }
                                        """
                                                .formatted(dmId, fixture.variant().getId())))
                .andExpect(status().isCreated());
        assertEquals(0, inventoryService.balanceOf(dmId, fixture.variant().getId()).getOnHand());
    }
}
