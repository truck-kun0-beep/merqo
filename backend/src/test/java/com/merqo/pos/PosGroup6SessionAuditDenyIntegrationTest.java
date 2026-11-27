package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.merqo.access.AccountType;
import com.merqo.common.config.SecurityConfiguration;
import com.merqo.common.web.CsrfTestSupport;

/**
 * Group 6 integration tests: closing session reconciliation across all 4 methods + refunds, audit
 * rows for returns/reprints/sessions, append-only repository interface checks, and default-deny
 * coverage on every Phase 3 endpoint.
 */
class PosGroup6SessionAuditDenyIntegrationTest extends PosIntegrationSupport {

    @Test
    @DisplayName(
            "closing computes server-side expected figures across all 4 methods & refunds, enforces difference note, and audits open/close/reprint/return")
    void sessionReconciliationAcrossMethodsAndAuditRows() throws Exception {
        UUID dmId = locationId("DM");
        ProductFixture fixture = createProductFixture("G6CLS", "400.00", "200.00");
        addOpeningStock(dmId, fixture.variant().getId(), 20);

        Cookie dmMgr =
                createAndSignIn("g6.mgr", AccountType.STAFF, "MANAGER", dmId, new BigDecimal("50"));
        UUID sessionId = openSession(dmMgr, dmId, "5000.00");

        // Verify CASH_SESSION_OPENED audited
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE action = 'CASH_SESSION_OPENED' AND entity_id = ?",
                        Integer.class,
                        sessionId.toString()));

        // 1. CASH sale qty 2 = 800.00
        String cashSaleJson =
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
        JsonNode cashSaleNode = MAPPER.readTree(cashSaleJson);
        UUID cashSaleId = UUID.fromString(cashSaleNode.get("id").asText());
        UUID cashLineId = UUID.fromString(cashSaleNode.get("lines").get(0).get("id").asText());

        // 2. BKASH, NAGAD, CARD sales qty 2 each = 800.00 each
        for (String[] nonCash :
                new String[][] {
                    {"BKASH", "G6BK" + shortId().toUpperCase()},
                    {"NAGAD", "G6NG" + shortId().toUpperCase()},
                    {"CARD", "4242"}
                }) {
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
                                              "payment": {"method": "%s", "reference": "%s"}
                                            }
                                            """
                                                    .formatted(
                                                            dmId,
                                                            fixture.variant().getId(),
                                                            nonCash[0],
                                                            nonCash[1])))
                    .andExpect(status().isCreated());
        }

        // 3. Reprint cash sale + audit RECEIPT_REPRINTED
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + cashSaleId + "/reprint")
                                .cookie(dmMgr)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDuplicate").value(true));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE action = 'RECEIPT_REPRINTED' AND entity_id = ?",
                        Integer.class,
                        cashSaleId.toString()));

        // 4. Cash refund of 1 unit (400.00) -> expected CASH drops from 5800.00 to 5400.00;
        // audits POS_RETURN_CREATED
        String retJson =
                mockMvc.perform(
                                post("/api/v1/pos/sales/" + cashSaleId + "/returns")
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
                                                        .formatted(cashLineId)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID returnId = UUID.fromString(MAPPER.readTree(retJson).get("id").asText());
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE action = 'POS_RETURN_CREATED' AND entity_id = ?",
                        Integer.class,
                        returnId.toString()));

        // 5. Difference on non-cash method (bKash 790 vs 800) with no note is refused
        mockMvc.perform(
                        post("/api/v1/pos/sessions/" + sessionId + "/close")
                                .cookie(dmMgr)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"countedCash\":\"5400.00\",\"countedBkash\":\"790.00\",\"countedNagad\":\"800.00\",\"countedCard\":\"800.00\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // 6. Close with exact server-expected figures across all 4 methods (no note needed)
        mockMvc.perform(
                        post("/api/v1/pos/sessions/" + sessionId + "/close")
                                .cookie(dmMgr)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"countedCash\":\"5400.00\",\"countedBkash\":\"800.00\",\"countedNagad\":\"800.00\",\"countedCard\":\"800.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.expectedCash").value("5400.00"))
                .andExpect(jsonPath("$.variance").value("0.00"));

        assertEquals(
                new BigDecimal("5400.00"),
                jdbcTemplate.queryForObject(
                        "SELECT expected_cash FROM cash_sessions WHERE id = ?",
                        BigDecimal.class,
                        sessionId));
        assertEquals(
                new BigDecimal("800.00"),
                jdbcTemplate.queryForObject(
                        "SELECT expected_bkash FROM cash_sessions WHERE id = ?",
                        BigDecimal.class,
                        sessionId));
        assertEquals(
                new BigDecimal("800.00"),
                jdbcTemplate.queryForObject(
                        "SELECT expected_nagad FROM cash_sessions WHERE id = ?",
                        BigDecimal.class,
                        sessionId));
        assertEquals(
                new BigDecimal("800.00"),
                jdbcTemplate.queryForObject(
                        "SELECT expected_card FROM cash_sessions WHERE id = ?",
                        BigDecimal.class,
                        sessionId));
    }

    @Test
    @DisplayName(
            "append-only repositories declare no update or delete methods and every new endpoint enforces default deny")
    void repositoriesAndDefaultDeny() throws Exception {
        for (Class<?> repo :
                List.of(
                        SaleRepository.class,
                        SaleLineRepository.class,
                        SalePaymentRepository.class,
                        SaleReturnRepository.class,
                        SaleReturnLineRepository.class,
                        ReceiptReprintRepository.class)) {
            for (Method m : repo.getDeclaredMethods()) {
                assertFalse(
                        m.isAnnotationPresent(Modifying.class),
                        repo.getSimpleName()
                                + " must not declare @Modifying method "
                                + m.getName());
                String lower = m.getName().toLowerCase();
                assertFalse(
                        lower.startsWith("delete")
                                || lower.startsWith("remove")
                                || lower.startsWith("update"),
                        repo.getSimpleName() + " must not declare mutation method " + m.getName());
            }
        }

        // Verify PUBLIC_ENDPOINTS includes zero POS or inspection paths
        for (String publicPath : SecurityConfiguration.PUBLIC_ENDPOINTS) {
            assertFalse(publicPath.startsWith("/api/v1/pos"));
            assertFalse(publicPath.startsWith("/api/v1/inventory"));
            assertFalse(publicPath.equals("/api/v1/auth/approval-pin"));
        }

        // Verify unauthenticated callers receive 401 UNAUTHENTICATED on every Phase 3 endpoint
        UUID rand = UUID.randomUUID();
        assertUnauthenticated(
                mockMvc.perform(
                        put("/api/v1/auth/approval-pin")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\":\"x\",\"pin\":\"4829\"}")));
        assertUnauthenticated(
                mockMvc.perform(
                        post("/api/v1/pos/sessions")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/pos/sessions/current")));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/pos/sessions/" + rand + "/summary")));
        assertUnauthenticated(
                mockMvc.perform(
                        post("/api/v1/pos/sessions/" + rand + "/close")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")));
        assertUnauthenticated(
                mockMvc.perform(
                        post("/api/v1/pos/approvals")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")));
        assertUnauthenticated(
                mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/pos/sales")));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/pos/sales/" + rand)));
        assertUnauthenticated(
                mockMvc.perform(
                        get("/api/v1/pos/sales/lookup").param("receiptNumber", "MQ-2026-000001")));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/pos/sales/" + rand + "/receipt")));
        assertUnauthenticated(
                mockMvc.perform(
                        post("/api/v1/pos/sales/" + rand + "/reprint")
                                .with(CsrfTestSupport.csrf())));
        assertUnauthenticated(
                mockMvc.perform(
                        post("/api/v1/pos/sales/" + rand + "/returns")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/pos/sales/" + rand + "/returns")));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/inventory/inspections")));
        assertUnauthenticated(
                mockMvc.perform(
                        post("/api/v1/inventory/inspections/resolve")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")));
    }

    private void assertUnauthenticated(org.springframework.test.web.servlet.ResultActions action)
            throws Exception {
        action.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }
}
