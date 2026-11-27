package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Group 4 integration tests proving branch scope isolation, swapped ID / tampered location
 * rejection across all POS endpoints, and Jackson recursive cost-privacy checks.
 */
class PosGroup4ScopeIntegrationTest extends PosIntegrationSupport {

    @Test
    @DisplayName(
            "enforces branch isolation, cashier isolation, swapped sale/line/location tampering, and Jackson cost privacy")
    void scopeTamperingAndCostPrivacy() throws Exception {
        UUID dmId = locationId("DM");
        UUID mpId = locationId("MP");
        UUID utId = locationId("UT");

        ProductFixture fixture = createProductFixture("G4SCP", "600.00", "320.00");
        addOpeningStock(dmId, fixture.variant().getId(), 10);
        addOpeningStock(mpId, fixture.variant().getId(), 10);
        addOpeningStock(utId, fixture.variant().getId(), 10);

        Cookie dmCashier1 =
                createAndSignIn("g4.c1", AccountType.STAFF, "CASHIER", dmId, new BigDecimal("5"));
        Cookie dmCashier2 =
                createAndSignIn("g4.c2", AccountType.STAFF, "CASHIER", dmId, new BigDecimal("5"));
        Cookie utCashier =
                createAndSignIn("g4.ut", AccountType.STAFF, "CASHIER", utId, new BigDecimal("5"));
        openSession(dmCashier1, dmId, "1000.00");
        openSession(dmCashier2, dmId, "1000.00");
        UUID utSessionId = openSession(utCashier, utId, "1000.00");

        // 1. DM cashier cannot open sessions or sell at Mirpur or Uttara (404)
        for (UUID otherLoc : new UUID[] {mpId, utId}) {
            assertOutOfScope(
                    mockMvc.perform(
                            post("/api/v1/pos/sessions")
                                    .cookie(dmCashier1)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"locationId\":\""
                                                    + otherLoc
                                                    + "\",\"openingCash\":\"500.00\"}")));
            assertOutOfScope(
                    mockMvc.perform(
                            post("/api/v1/pos/sales")
                                    .cookie(dmCashier1)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {
                                              "locationId": "%s",
                                              "lines": [{"variantId": "%s", "quantity": 1}],
                                              "payment": {"method": "CASH", "tendered": "600.00"}
                                            }
                                            """
                                                    .formatted(
                                                            otherLoc, fixture.variant().getId()))));
        }

        // Create DM sale (cashier1), DM sale (cashier2), UT sale (utCashier)
        JsonNode dmSale1 = createCashSale(dmCashier1, dmId, fixture.variant().getId(), "600.00");
        JsonNode dmSale2 = createCashSale(dmCashier2, dmId, fixture.variant().getId(), "600.00");
        JsonNode utSale = createCashSale(utCashier, utId, fixture.variant().getId(), "600.00");

        UUID dmSale1Id = UUID.fromString(dmSale1.get("id").asText());
        UUID dmSale2Id = UUID.fromString(dmSale2.get("id").asText());
        String dmSale2Receipt = dmSale2.get("receiptNumber").asText();
        UUID utSaleId = UUID.fromString(utSale.get("id").asText());
        UUID utSaleLineId = UUID.fromString(utSale.get("lines").get(0).get("id").asText());

        // 2. DM cashier1 cannot view, lookup, receipt, or reprint DM cashier2's sale (404)
        assertOutOfScope(mockMvc.perform(get("/api/v1/pos/sales/" + dmSale2Id).cookie(dmCashier1)));
        assertOutOfScope(
                mockMvc.perform(
                        get("/api/v1/pos/sales/" + dmSale2Id + "/receipt").cookie(dmCashier1)));
        assertOutOfScope(
                mockMvc.perform(
                        post("/api/v1/pos/sales/" + dmSale2Id + "/reprint")
                                .cookie(dmCashier1)
                                .with(CsrfTestSupport.csrf())));
        assertOutOfScope(
                mockMvc.perform(
                        get("/api/v1/pos/sales/lookup")
                                .param("receiptNumber", dmSale2Receipt)
                                .cookie(dmCashier1)));

        // 3. Swapped IDs & tampered body locationIds rejected:
        //    - manager-dm returns/inspects/closes UT records -> 404
        Cookie managerDm = signIn("manager-dm");
        assertOutOfScope(mockMvc.perform(get("/api/v1/pos/sales/" + utSaleId).cookie(managerDm)));
        assertOutOfScope(
                mockMvc.perform(
                        get("/api/v1/pos/sales/" + utSaleId + "/returns").cookie(managerDm)));
        assertOutOfScope(
                mockMvc.perform(
                        post("/api/v1/pos/sales/" + utSaleId + "/returns")
                                .cookie(managerDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "DEFECTIVE",
                                          "lines": [{"saleLineId": "%s", "quantity": 1, "disposition": "RESTOCK"}]
                                        }
                                        """
                                                .formatted(utSaleLineId))));
        assertOutOfScope(
                mockMvc.perform(
                        post("/api/v1/pos/sessions/" + utSessionId + "/close")
                                .cookie(managerDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"countedCash\":\"1600.00\"}")));

        //    - Swapped saleLineId from UT passed inside DM sale return -> 404 NOT_FOUND (never
        // returns UT item)
        Cookie dmMgrWithSession =
                createAndSignIn(
                        "g4.dmrm", AccountType.STAFF, "MANAGER", dmId, new BigDecimal("50"));
        openSession(dmMgrWithSession, dmId, "500.00");
        assertOutOfScope(
                mockMvc.perform(
                        post("/api/v1/pos/sales/" + dmSale1Id + "/returns")
                                .cookie(dmMgrWithSession)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "DEFECTIVE",
                                          "lines": [{"saleLineId": "%s", "quantity": 1, "disposition": "RESTOCK"}]
                                        }
                                        """
                                                .formatted(utSaleLineId))));

        //    - Manager-dm gets 404 when resolving inspections at UT with tampered body locationId
        Cookie owner = signIn("owner");
        assertOutOfScope(
                mockMvc.perform(
                        post("/api/v1/inventory/inspections/resolve")
                                .cookie(managerDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"locationId":"%s","variantId":"%s","quantity":1,"outcome":"RESTOCK"}
                                        """
                                                .formatted(utId, fixture.variant().getId()))));

        // 4. Cost privacy checks with Jackson on cashier's sale, receipt, history, lookup & reprint
        String saleJson =
                mockMvc.perform(get("/api/v1/pos/sales/" + dmSale1Id).cookie(dmCashier1))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String receiptJson =
                mockMvc.perform(
                                get("/api/v1/pos/sales/" + dmSale1Id + "/receipt")
                                        .cookie(dmCashier1))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String historyJson =
                mockMvc.perform(
                                get("/api/v1/pos/sales")
                                        .param("q", dmSale1.get("receiptNumber").asText())
                                        .cookie(dmCashier1))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String lookupJson =
                mockMvc.perform(
                                get("/api/v1/pos/sales/lookup")
                                        .param(
                                                "receiptNumber",
                                                dmSale1.get("receiptNumber").asText())
                                        .cookie(dmCashier1))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String catalogLookupJson =
                mockMvc.perform(
                                get("/api/v1/catalog/lookup")
                                        .param("barcode", fixture.barcode())
                                        .param("locationId", dmId.toString())
                                        .cookie(dmCashier1))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertNoForbiddenCostKeys(MAPPER.readTree(saleJson), "GET /pos/sales/{id}");
        assertNoForbiddenCostKeys(MAPPER.readTree(receiptJson), "GET /pos/sales/{id}/receipt");
        assertNoForbiddenCostKeys(MAPPER.readTree(historyJson), "GET /pos/sales");
        assertNoForbiddenCostKeys(MAPPER.readTree(lookupJson), "GET /pos/sales/lookup");

        JsonNode catNode = MAPPER.readTree(catalogLookupJson);
        assertFalse(catNode.has("cost"));
        assertFalse(catNode.has("costPrice"));
        assertFalse(catNode.has("unitCost"));
        assertFalse(catNode.has("margin"));
        assertFalse(catNode.has("profit"));
        assertFalse(catNode.get("variant").has("costPrice"));

        // Owner sees unitCost and belowCostWarnings on the exact same sale
        JsonNode ownerSale =
                MAPPER.readTree(
                        mockMvc.perform(get("/api/v1/pos/sales/" + dmSale1Id).cookie(owner))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertTrue(ownerSale.has("belowCostWarnings"));
        assertTrue(ownerSale.get("lines").get(0).has("unitCost"));
        assertTrue(
                MAPPER.readTree(
                                mockMvc.perform(
                                                get("/api/v1/pos/sales/" + dmSale1Id)
                                                        .cookie(managerDm))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("lines")
                        .get(0)
                        .has("unitCost"));
    }

    private JsonNode createCashSale(Cookie cookie, UUID locationId, UUID variantId, String tendered)
            throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(cookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "locationId": "%s",
                                                  "lines": [{"variantId": "%s", "quantity": 1}],
                                                  "payment": {"method": "CASH", "tendered": "%s"}
                                                }
                                                """
                                                        .formatted(
                                                                locationId, variantId, tendered)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return MAPPER.readTree(body);
    }
}
