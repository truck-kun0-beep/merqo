package com.merqo.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.merqo.access.AccountType;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditLog;
import com.merqo.audit.AuditLogRepository;
import com.merqo.common.config.SecurityConfiguration;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.pos.PosIntegrationSupport;

/**
 * Slice F hardening tests: default deny on all Phase 5 endpoints, scope &amp; tampered-ID
 * isolation, Jackson whole-body cost privacy, card-number non-echo &amp; redaction, all 7
 * append-only triggers &amp; repositories, all 9 Phase 5 audit actions, and system-wide financial
 * invariants.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentHardeningIntegrationTest extends PosIntegrationSupport {

    private static final Set<String> FORBIDDEN_PAYMENT_COST_KEYS =
            Set.of(
                    "cost",
                    "costPrice",
                    "unitCost",
                    "unitCostSnapshot",
                    "valueAtCost",
                    "margin",
                    "profit",
                    "belowCost",
                    "belowCostWarnings");

    @Autowired private ObjectMapper json;
    @Autowired private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("default deny, branch scope isolation, COD permission isolation, and swapped IDs")
    void defaultDenyScopeAndTamperedIds() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("PHARD", "500.00", "200.00");
        addOpeningStock(whId, item.variant().getId(), 10);

        Cookie ownerCookie = signIn("owner");
        Cookie managerDm = signIn("manager-dm");
        Cookie cashierDm = signIn("cashier");
        Cookie whNoCod =
                createAndSignIn("phard.wh", AccountType.STAFF, "ONLINE_ORDER_STAFF", whId, null);

        // Create FULL_PREPAID order + payment + refund at WH so IDs exist
        UUID orderId = createOrder(ownerCookie, item.variant().getSku(), "FULL_PREPAID", false);
        UUID payId = recordAndPayPrep(ownerCookie, orderId, "PHD" + shortId().toUpperCase());
        UUID rfdId = createRequestedRefund(ownerCookie, orderId, "200.00");

        // 1. Default deny: session-less calls on every Phase 5 endpoint return 401 UNAUTHENTICATED
        for (String publicPath : SecurityConfiguration.PUBLIC_ENDPOINTS) {
            assertFalse(publicPath.startsWith("/api/v1/payments"));
            assertFalse(publicPath.startsWith("/api/v1/refunds"));
            assertFalse(publicPath.startsWith("/api/v1/cod"));
        }
        UUID rand = UUID.randomUUID();
        assert401(get("/api/v1/orders/" + rand + "/payments"));
        assert401(get("/api/v1/orders/" + rand + "/payments/claim-prefill"));
        assert401(
                post("/api/v1/orders/" + rand + "/payments")
                        .with(CsrfTestSupport.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));
        assert401(
                post("/api/v1/payments/" + rand + "/verify")
                        .with(CsrfTestSupport.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));
        assert401(post("/api/v1/payments/" + rand + "/check").with(CsrfTestSupport.csrf()));
        assert401(get("/api/v1/payments/pending"));
        assert401(
                post("/api/v1/orders/" + rand + "/refunds")
                        .with(CsrfTestSupport.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));
        assert401(post("/api/v1/refunds/" + rand + "/approve").with(CsrfTestSupport.csrf()));
        assert401(post("/api/v1/refunds/" + rand + "/reject").with(CsrfTestSupport.csrf()));
        assert401(
                post("/api/v1/refunds/" + rand + "/pay")
                        .with(CsrfTestSupport.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));
        assert401(get("/api/v1/refunds"));
        assert401(get("/api/v1/refunds/due"));
        assert401(
                post("/api/v1/orders/" + rand + "/cod-collections")
                        .with(CsrfTestSupport.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));
        assert401(
                post("/api/v1/cod/settlements")
                        .with(CsrfTestSupport.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));
        assert401(get("/api/v1/cod/outstanding"));
        assert401(get("/api/v1/cod/settlements"));
        assert401(get("/api/v1/cod/settlements/" + rand));
        assert401(get("/api/v1/payments/history"));
        assert401(get("/api/v1/payments/summary"));

        // 2. Both manager-dm and cashier-dm receive 404 on warehouse payments, refunds, and pending
        for (Cookie branchUser : List.of(managerDm, cashierDm)) {
            mockMvc.perform(get("/api/v1/orders/" + orderId + "/payments").cookie(branchUser))
                    .andExpect(status().isNotFound());
            mockMvc.perform(
                            get("/api/v1/orders/" + orderId + "/payments/claim-prefill")
                                    .cookie(branchUser))
                    .andExpect(status().isNotFound());
            mockMvc.perform(
                            post("/api/v1/orders/" + orderId + "/payments")
                                    .cookie(branchUser)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"method\":\"BKASH\",\"amount\":100.00,\"reference\":\"BRH1234\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(
                            post("/api/v1/payments/" + payId + "/verify")
                                    .cookie(branchUser)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"decision\":\"REJECTED\",\"evidence\":\"Nope\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/payments/pending").cookie(branchUser))
                    .andExpect(status().isNotFound());
            mockMvc.perform(
                            post("/api/v1/orders/" + orderId + "/refunds")
                                    .cookie(branchUser)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"amount\":100.00,\"reason\":\"CUSTOMER_REQUEST\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(
                            post("/api/v1/refunds/" + rfdId + "/approve")
                                    .cookie(branchUser)
                                    .with(CsrfTestSupport.csrf()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/refunds").cookie(branchUser))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/refunds/due").cookie(branchUser))
                    .andExpect(status().isNotFound());
        }

        // 3. WH user without COD_SETTLEMENT_MANAGE gets 403 on COD & settlements
        mockMvc.perform(get("/api/v1/cod/outstanding").cookie(whNoCod))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/cod/settlements").cookie(whNoCod))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/cod-collections")
                                .cookie(whNoCod)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"courier\":\"PATHAO\",\"amount\":100.00}"))
                .andExpect(status().isForbidden());

        // 4. Swapped / unknown IDs return 404
        mockMvc.perform(get("/api/v1/orders/" + rand + "/payments").cookie(ownerCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/v1/payments/" + rand + "/verify")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"REJECTED\",\"evidence\":\"Missing\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/v1/refunds/" + rand + "/approve")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/cod/settlements/" + rand).cookie(ownerCookie))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName(
            "cost privacy, PAN non-echo/rejection, 7 append-only triggers/repos, 9 audit actions, and invariants")
    void costPrivacyPanTriggersAuditAndInvariants() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("PINV", "500.00", "220.00");
        addOpeningStock(whId, item.variant().getId(), 15);
        Cookie ownerCookie = signIn("owner");

        // 1. Card PANs never accepted or echoed across references, notes, evidence
        UUID ordPre = createOrder(ownerCookie, item.variant().getSku(), "FULL_PREPAID", false);
        String pan = "4111-1111-1111-1111";
        String panRefResp =
                mockMvc.perform(
                                post("/api/v1/orders/" + ordPre + "/payments")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"method":"CARD","amount":500.00,"reference":"%s"}
                                                """
                                                        .formatted(pan)))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.CARD_NUMBER_REJECTED))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertFalse(panRefResp.contains("4111"));

        String panNoteResp =
                mockMvc.perform(
                                post("/api/v1/orders/" + ordPre + "/payments")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"method":"CARD","amount":500.00,"reference":"AUTH99","payerNote":"Card 4111 1111 1111 1111"}
                                                """))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.CARD_NUMBER_REJECTED))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertFalse(panNoteResp.contains("4111"));

        // 2. Drive ALL 9 Phase 5 audit actions cleanly
        UUID pVerify =
                recordPayment(
                        ownerCookie, ordPre, "BKASH", "250.00", "A9V" + shortId().toUpperCase());
        UUID pReject =
                recordPayment(
                        ownerCookie, ordPre, "BKASH", "250.00", "A9R" + shortId().toUpperCase());

        // PAN inside verification evidence rejected without echo
        String panEvResp =
                mockMvc.perform(
                                post("/api/v1/payments/" + pVerify + "/verify")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"decision":"VERIFIED","confirmedAmount":250.00,"evidence":"Card 4111111111111111"}
                                                """))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.errorCode").value(ErrorCodes.CARD_NUMBER_REJECTED))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertFalse(panEvResp.contains("4111"));

        verifyPayment(ownerCookie, pVerify, "250.00");
        mockMvc.perform(
                        post("/api/v1/payments/" + pReject + "/verify")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"decision\":\"REJECTED\",\"evidence\":\"Declined slip\"}"))
                .andExpect(status().isOk());

        UUID rAppPay = createRequestedRefund(ownerCookie, ordPre, "100.00");
        UUID rRej = createRequestedRefund(ownerCookie, ordPre, "50.00");
        mockMvc.perform(
                        post("/api/v1/refunds/" + rAppPay + "/approve")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/refunds/" + rRej + "/reject")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"Not eligible\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/refunds/" + rAppPay + "/pay")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"payoutMethod\":\"CASH\",\"note\":\"Handed cash\"}"))
                .andExpect(status().isOk());

        UUID ordCod = createOrder(ownerCookie, item.variant().getSku(), "COD", true);
        mockMvc.perform(
                        post("/api/v1/orders/" + ordCod + "/cod-collections")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"courier\":\"PATHAO\",\"amount\":500.00}"))
                .andExpect(status().isCreated());
        String stlJson =
                mockMvc.perform(
                                post("/api/v1/cod/settlements")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "courier": "PATHAO",
                                                  "reference": "A9STL-%s",
                                                  "receiveMethod": "BANK",
                                                  "amountReceived": 485.00,
                                                  "lines": [{"orderId": "%s", "codCollectedAmount": 500.00, "fee": 15.00}]
                                                }
                                                """
                                                        .formatted(
                                                                shortId().toUpperCase(), ordCod)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID stlId = UUID.fromString(json.readTree(stlJson).get("id").asString());

        // Verify ALL 9 Phase 5 audit actions present & free of card digits
        Set<AuditAction> expected =
                EnumSet.of(
                        AuditAction.PAYMENT_RECORDED,
                        AuditAction.PAYMENT_VERIFIED,
                        AuditAction.PAYMENT_REJECTED,
                        AuditAction.REFUND_REQUESTED,
                        AuditAction.REFUND_APPROVED,
                        AuditAction.REFUND_REJECTED,
                        AuditAction.REFUND_PAID,
                        AuditAction.COD_COLLECTION_RECORDED,
                        AuditAction.COD_SETTLEMENT_RECORDED);
        Set<String> seen = new java.util.HashSet<>();
        for (AuditLog row : auditLogRepository.findAll()) {
            seen.add(row.getAction());
            String txt = String.valueOf(row.getOldValues()) + String.valueOf(row.getNewValues());
            assertFalse(txt.contains("4111111111111"));
        }
        for (AuditAction a : expected) {
            assertTrue(seen.contains(a.name()), "Missing Phase 5 audit action: " + a.name());
        }

        // 3. Cost privacy: parse EVERY payment/refund/COD/history/summary/order response with
        // Jackson
        for (String endpoint :
                List.of(
                        "/api/v1/orders/" + ordPre,
                        "/api/v1/orders/" + ordPre + "/payments",
                        "/api/v1/payments/pending",
                        "/api/v1/refunds",
                        "/api/v1/refunds/due",
                        "/api/v1/cod/outstanding",
                        "/api/v1/cod/settlements",
                        "/api/v1/cod/settlements/" + stlId,
                        "/api/v1/payments/history",
                        "/api/v1/payments/summary")) {
            String resp =
                    mockMvc.perform(get(endpoint).cookie(ownerCookie))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertCostKeysAbsent(json.readTree(resp), endpoint);
        }

        // 4. Append-only DB triggers block UPDATE and DELETE on ALL 7 Phase 5 tables
        UUID payDecisionId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM payment_decisions WHERE payment_id = ?",
                        UUID.class,
                        pVerify);
        UUID rfdEventId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM refund_events WHERE refund_id = ? LIMIT 1",
                        UUID.class,
                        rAppPay);
        UUID codColId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM cod_collections WHERE order_id = ? LIMIT 1",
                        UUID.class,
                        ordCod);
        UUID stlLineId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM courier_settlement_lines WHERE settlement_id = ? LIMIT 1",
                        UUID.class,
                        stlId);

        assertAppendOnly("order_payments", "payer_note = 'TAMPERED'", pVerify);
        assertAppendOnly("payment_decisions", "evidence = 'TAMPERED'", payDecisionId);
        assertAppendOnly("refund_requests", "note = 'TAMPERED'", rAppPay);
        assertAppendOnly("refund_events", "note = 'TAMPERED'", rfdEventId);
        assertAppendOnly("cod_collections", "note = 'TAMPERED'", codColId);
        assertAppendOnly("courier_settlements", "note = 'TAMPERED'", stlId);
        assertAppendOnly("courier_settlement_lines", "fee = 999.00", stlLineId);

        // 5. Append-only repositories declare no @Modifying or update/delete methods
        for (Class<?> repo :
                List.of(
                        OrderPaymentRepository.class,
                        PaymentDecisionRepository.class,
                        RefundRequestRepository.class,
                        RefundEventRepository.class,
                        CodCollectionRepository.class,
                        CourierSettlementRepository.class,
                        CourierSettlementLineRepository.class)) {
            for (Method m : repo.getDeclaredMethods()) {
                assertFalse(
                        m.isAnnotationPresent(Modifying.class),
                        repo.getSimpleName() + " must not declare @Modifying " + m.getName());
                String lower = m.getName().toLowerCase();
                assertFalse(
                        lower.startsWith("delete")
                                || lower.startsWith("remove")
                                || lower.startsWith("update"),
                        repo.getSimpleName() + " must not declare mutation method " + m.getName());
            }
        }

        // 6. Database-wide financial invariants
        invariant_refundedAndReservedBoundedByVerifiedPaid();
        invariant_codSettledBoundedByCollectedAndExpected();
        invariant_settlementSumLineNetPlusDifferenceEqualsAmountReceived();
    }

    @Test
    @DisplayName(
            "0 <= refundedPaid <= verifiedPaid, refundReserved + refundedPaid <= verifiedPaid, verifiedPaid <= requiredPrepayment")
    void invariant_refundedAndReservedBoundedByVerifiedPaid() {
        Integer violations =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM (
                            SELECT o.id,
                                   CASE
                                     WHEN o.payment_mode = 'COD' THEN 0
                                     WHEN o.payment_mode = 'ADVANCE_COD' THEN o.advance_amount
                                     ELSE o.total
                                   END AS required_prepay,
                                   COALESCE((SELECT SUM(op.amount) FROM order_payments op
                                             JOIN payment_decisions pd ON pd.payment_id = op.id
                                             WHERE op.order_id = o.id AND pd.decision = 'VERIFIED'), 0) AS verified_paid,
                                   COALESCE((SELECT SUM(rr.amount) FROM refund_requests rr
                                             WHERE rr.order_id = o.id AND EXISTS (
                                                 SELECT 1 FROM refund_events re WHERE re.refund_id = rr.id AND re.event_type = 'PAID'
                                             )), 0) AS refunded_paid,
                                   COALESCE((SELECT SUM(rr.amount) FROM refund_requests rr
                                             WHERE rr.order_id = o.id AND NOT EXISTS (
                                                 SELECT 1 FROM refund_events re WHERE re.refund_id = rr.id AND re.event_type IN ('PAID', 'REJECTED')
                                             )), 0) AS refund_reserved
                            FROM orders o
                        ) t
                        WHERE t.refunded_paid < 0
                           OR t.refunded_paid > t.verified_paid
                           OR (t.refund_reserved + t.refunded_paid) > t.verified_paid
                           OR t.verified_paid > t.required_prepay
                        """,
                        Integer.class);
        assertEquals(0, violations);
    }

    @Test
    @DisplayName("codSettledGross <= codCollected <= codExpected for every order")
    void invariant_codSettledBoundedByCollectedAndExpected() {
        Integer violations =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM (
                            SELECT o.id,
                                   CASE WHEN o.payment_mode = 'FULL_PREPAID' THEN 0 ELSE o.cod_amount END AS cod_expected,
                                   COALESCE((SELECT SUM(cc.amount) FROM cod_collections cc WHERE cc.order_id = o.id), 0) AS cod_collected,
                                   COALESCE((SELECT SUM(csl.cod_collected_amount) FROM courier_settlement_lines csl WHERE csl.order_id = o.id), 0) AS cod_settled
                            FROM orders o
                        ) t
                        WHERE t.cod_settled > t.cod_collected
                           OR t.cod_collected > t.cod_expected
                        """,
                        Integer.class);
        assertEquals(0, violations);
    }

    @Test
    @DisplayName("sum(line net) + difference == amount_received for every courier settlement")
    void invariant_settlementSumLineNetPlusDifferenceEqualsAmountReceived() {
        Integer violations =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM (
                            SELECT cs.id, cs.amount_received, cs.difference,
                                   COALESCE((SELECT SUM(csl.net) FROM courier_settlement_lines csl WHERE csl.settlement_id = cs.id), 0) AS sum_net
                            FROM courier_settlements cs
                        ) s
                        WHERE (s.sum_net + s.difference) <> s.amount_received
                        """,
                        Integer.class);
        assertEquals(0, violations);
    }

    private void assertAppendOnly(String table, String setClause, UUID id) {
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbcTemplate.update(
                                "UPDATE " + table + " SET " + setClause + " WHERE id = ?", id));
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update("DELETE FROM " + table + " WHERE id = ?", id));
    }

    private void assertCostKeysAbsent(JsonNode node, String context) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (String key : node.propertyNames()) {
                assertFalse(
                        FORBIDDEN_PAYMENT_COST_KEYS.contains(key),
                        "Forbidden cost key '" + key + "' found in " + context);
                assertCostKeysAbsent(node.get(key), context + "." + key);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertCostKeysAbsent(child, context + "[]");
            }
        }
    }

    private void assert401(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        mockMvc.perform(req)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    private UUID createOrder(Cookie actor, String sku, String mode, boolean confirmNow)
            throws Exception {
        String body =
                """
                {
                  "customerName": "Hardening Customer",
                  "phone": "01712345678",
                  "address": "Banani",
                  "lines": [{"sku": "%s", "quantity": 1}],
                  "paymentMode": "%s",
                  "confirmNow": %s
                }
                """
                        .formatted(sku, mode, confirmNow);
        String resp =
                mockMvc.perform(
                                post("/api/v1/orders/manual")
                                        .cookie(actor)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asString());
    }

    private UUID recordPayment(
            Cookie actor, UUID orderId, String method, String amount, String reference)
            throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/orders/" + orderId + "/payments")
                                        .cookie(actor)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"method":"%s","amount":%s,"reference":"%s"}
                                                """
                                                        .formatted(method, amount, reference)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private void verifyPayment(Cookie actor, UUID paymentId, String amount) throws Exception {
        mockMvc.perform(
                        post("/api/v1/payments/" + paymentId + "/verify")
                                .cookie(actor)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"decision":"VERIFIED","confirmedAmount":%s,"evidence":"Verified ok"}
                                        """
                                                .formatted(amount)))
                .andExpect(status().isOk());
    }

    private UUID recordAndPayPrep(Cookie actor, UUID orderId, String ref) throws Exception {
        UUID p = recordPayment(actor, orderId, "BKASH", "500.00", ref);
        verifyPayment(actor, p, "500.00");
        return p;
    }

    private UUID createRequestedRefund(Cookie actor, UUID orderId, String amount) throws Exception {
        String resp =
                mockMvc.perform(
                                post("/api/v1/orders/" + orderId + "/refunds")
                                        .cookie(actor)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"amount\":"
                                                        + amount
                                                        + ",\"reason\":\"CUSTOMER_REQUEST\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asString());
    }
}
