package com.merqo.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.merqo.common.web.CsrfTestSupport;
import com.merqo.payments.CourierSettlement;
import com.merqo.payments.CourierSettlementRepository;
import com.merqo.payments.OrderPayment;
import com.merqo.payments.OrderPaymentRepository;
import com.merqo.payments.PaymentDecisionRecord;
import com.merqo.payments.PaymentDecisionRepository;
import com.merqo.payments.RefundEventRepository;
import com.merqo.payments.RefundRequest;
import com.merqo.payments.RefundRequestRepository;
import com.merqo.payments.domain.OnlinePaymentMethod;
import com.merqo.payments.domain.PaymentStatus;
import com.merqo.payments.domain.RefundStatus;
import com.merqo.payments.domain.SettlementCalculator.DifferenceKind;
import com.merqo.pos.PosIntegrationSupport;

/** Verifies {@link DemoOrdersPaymentsContributor} seeding and Demo Console simulate-payment. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoPaymentsIntegrationTest extends PosIntegrationSupport {

    @Autowired private DemoOrdersPaymentsContributor contributor;
    @Autowired private OrderPaymentRepository paymentRepository;
    @Autowired private PaymentDecisionRepository decisionRepository;
    @Autowired private RefundRequestRepository refundRequestRepository;
    @Autowired private RefundEventRepository refundEventRepository;
    @Autowired private CourierSettlementRepository settlementRepository;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName(
            "demo payments contributor seeds idempotent payments, refunds, COD gap, and settlements")
    void demoPaymentsContributorIdempotentAndComplete() throws Exception {
        Set<OnlinePaymentMethod> seenMethods = EnumSet.noneOf(OnlinePaymentMethod.class);
        Set<PaymentStatus> seenStatuses = EnumSet.noneOf(PaymentStatus.class);

        for (int i = 1; i <= 12; i++) {
            String key = String.format("demo-pay-%02d", i);
            OrderPayment p = paymentRepository.findByDemoKey(key).orElse(null);
            assertNotNull(p, "Missing demo payment: " + key);
            seenMethods.add(p.getMethod());
            PaymentDecisionRecord dec = decisionRepository.findByPaymentId(p.getId()).orElse(null);
            seenStatuses.add(PaymentStatus.derive(dec == null ? null : dec.getDecision()));
        }
        assertEquals(4, seenMethods.size(), "All 4 payment methods must be seeded");
        assertEquals(3, seenStatuses.size(), "All 3 derived payment statuses must be seeded");

        // All 3 refund states seeded
        Set<RefundStatus> refundStatuses = EnumSet.noneOf(RefundStatus.class);
        for (int i = 1; i <= 3; i++) {
            RefundRequest r =
                    refundRequestRepository
                            .findByDemoKey(String.format("demo-rfd-%02d", i))
                            .orElse(null);
            assertNotNull(r);
            var events = refundEventRepository.findByRefundIdOrderByCreatedAtAsc(r.getId());
            refundStatuses.add(
                    RefundStatus.derive(events.stream().map(e -> e.getEventType()).toList()));
        }
        assertTrue(refundStatuses.contains(RefundStatus.REQUESTED));
        assertTrue(refundStatuses.contains(RefundStatus.APPROVED));
        assertTrue(refundStatuses.contains(RefundStatus.PAID));

        // Settlements: 1 NONE and 1 SHORT
        CourierSettlement exact = settlementRepository.findByDemoKey("demo-stl-01").orElse(null);
        CourierSettlement shortStl = settlementRepository.findByDemoKey("demo-stl-02").orElse(null);
        assertNotNull(exact);
        assertNotNull(shortStl);
        assertEquals(DifferenceKind.NONE, exact.getDifferenceKind());
        assertEquals(DifferenceKind.SHORT, shortStl.getDifferenceKind());

        // Re-running contributor is strictly idempotent
        long payBefore = paymentRepository.count();
        long stlBefore = settlementRepository.count();
        contributor.seed();
        assertEquals(payBefore, paymentRepository.count());
        assertEquals(stlBefore, settlementRepository.count());

        Cookie ownerCookie = signIn("owner");

        // Verify pending overdue payment and >7d COD gap show via API
        JsonNode pending =
                json.readTree(
                        mockMvc.perform(get("/api/v1/payments/pending").cookie(ownerCookie))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        boolean sawOverdue = false;
        for (JsonNode row : pending) {
            if (row.get("overdue").asBoolean()) {
                sawOverdue = true;
            }
        }
        assertTrue(sawOverdue, "Seeded overdue payment should appear in GET /payments/pending");

        JsonNode outstanding =
                json.readTree(
                        mockMvc.perform(get("/api/v1/cod/outstanding").cookie(ownerCookie))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        boolean sawGap = false;
        for (JsonNode row : outstanding.get("orders")) {
            if (row.get("gap").asBoolean()) {
                sawGap = true;
            }
        }
        assertTrue(sawGap, "Seeded >7d COD collection must appear as gap=true");

        // Demo Console POST /demo/simulate-payment creates mock tx & lets MockPaymentVerifier
        // confirm!
        UUID cardPendId = paymentRepository.findByDemoKey("demo-pay-05").orElseThrow().getId();
        mockMvc.perform(
                        post("/api/v1/demo/simulate-payment")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "method": "BKASH",
                                          "reference": "DCONSOLE%s",
                                          "amount": 500.00,
                                          "senderHint": "01711223344",
                                          "variant": "MATCH"
                                        }
                                        """
                                                .formatted(shortId().toUpperCase())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variant").value("MATCH"))
                .andExpect(jsonPath("$.alreadyClaimed").value(false));

        assertNotNull(cardPendId);
    }
}
