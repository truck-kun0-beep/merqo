package com.merqo.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.merqo.access.AccountType;
import com.merqo.access.PermissionResolver.OverrideEffect;
import com.merqo.access.UserPermissionOverride;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.pos.PosIntegrationSupport;

/**
 * Integration tests for Slice C: refund request caps, due list, four-eyes approval policy,
 * transition rules, payout uniqueness &amp; CASH note validation, netHeld update, and concurrency
 * races.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefundsIntegrationTest extends PosIntegrationSupport {

    @Autowired private ObjectMapper json;
    @Autowired private RefundEventRepository refundEventRepository;

    @Test
    @DisplayName(
            "refund lifecycle: unverified refused, partial/full caps, due list, four-eyes, pay, netHeld, and duplicate payout ref")
    void refundLifecycleDueListFourEyesAndPayout() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("RFD", "600.00", "250.00");
        addOpeningStock(whId, item.variant().getId(), 10);

        Cookie ownerCookie = signIn("owner");

        // Two WH finance/order users: requester (ONLINE_ORDER_STAFF + REFUND_APPROVE) and second
        // approver
        Cookie requester =
                createAndSignIn("rfd.req", AccountType.STAFF, "ONLINE_ORDER_STAFF", whId, null);
        grantRefundApprove("rfd.req%", whId);
        Cookie approver =
                createAndSignIn("rfd.app", AccountType.STAFF, "ONLINE_ORDER_STAFF", whId, null);
        grantRefundApprove("rfd.app%", whId);

        // Order 1: FULL_PREPAID 600.00 -> payment still PENDING_VERIFICATION cannot be refunded
        String ord1Body = createPrepaidOrderAndGetBody(ownerCookie, item.variant().getSku());
        UUID ord1Id = UUID.fromString(json.readTree(ord1Body).get("id").asString());
        String ord1Number = json.readTree(ord1Body).get("orderNumber").asString();

        UUID pay1Id =
                recordPayment(
                        requester, ord1Id, "BKASH", "600.00", "RFDP1" + shortId().toUpperCase());

        mockMvc.perform(
                        post("/api/v1/orders/" + ord1Id + "/refunds")
                                .cookie(requester)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":100.00,\"reason\":\"CUSTOMER_REQUEST\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.REFUND_EXCEEDS_PAID));

        // Verify payment 1 -> 600.00 verifiedPaid
        verifyPayment(approver, pay1Id, "600.00");

        // Cancel order -> appears in GET /refunds/due and sets refundDue=true
        mockMvc.perform(
                        post("/api/v1/orders/" + ord1Id + "/cancel")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"CUSTOMER_REQUESTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.paymentPosition.refundDue").value(true));

        mockMvc.perform(get("/api/v1/refunds/due").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.orderId=='" + ord1Id + "')].refundable").value(600.00));

        // OTHER reason without note -> 422 REASON_REQUIRED
        mockMvc.perform(
                        post("/api/v1/orders/" + ord1Id + "/refunds")
                                .cookie(requester)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":200.00,\"reason\":\"OTHER\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.REASON_REQUIRED));

        // Partial refund 1: 400.00 REQUESTED by requester -> order disappears from /refunds/due
        String rfd1Json =
                mockMvc.perform(
                                post("/api/v1/orders/" + ord1Id + "/refunds")
                                        .cookie(requester)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"amount\":400.00,\"reason\":\"ORDER_CANCELLED\",\"note\":\"Partial 1\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("REQUESTED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID rfd1Id = UUID.fromString(json.readTree(rfd1Json).get("id").asString());

        mockMvc.perform(get("/api/v1/refunds/due").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.orderId=='" + ord1Id + "')]").isEmpty());

        // Second request for 201.00 exceeds remaining refundable (200.00) -> 409
        // REFUND_EXCEEDS_PAID
        mockMvc.perform(
                        post("/api/v1/orders/" + ord1Id + "/refunds")
                                .cookie(requester)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":201.00,\"reason\":\"ORDER_CANCELLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.REFUND_EXCEEDS_PAID));

        // Pay before approve -> 409 INVALID_TRANSITION
        mockMvc.perform(
                        post("/api/v1/refunds/" + rfd1Id + "/pay")
                                .cookie(requester)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"payoutMethod\":\"CASH\",\"note\":\"Cash drawer\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_TRANSITION));

        // Self-approval refused for non-owner requester -> 422 SELF_APPROVAL_NOT_ALLOWED
        mockMvc.perform(
                        post("/api/v1/refunds/" + rfd1Id + "/approve")
                                .cookie(requester)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"Self approve attempt\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.SELF_APPROVAL_NOT_ALLOWED));

        // Approver approves -> APPROVED; double approve -> 409 INVALID_TRANSITION
        mockMvc.perform(
                        post("/api/v1/refunds/" + rfd1Id + "/approve")
                                .cookie(approver)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"Approved partial\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(
                        post("/api/v1/refunds/" + rfd1Id + "/approve")
                                .cookie(approver)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_TRANSITION));

        // Pay rfd1 with BKASH -> PAID; netHeld drops from 600 to 200; due list shows 200 again!
        String payoutRef = "RFDPAY" + shortId().toUpperCase();
        mockMvc.perform(
                        post("/api/v1/refunds/" + rfd1Id + "/pay")
                                .cookie(requester)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"payoutMethod":"BKASH","payoutReference":"%s","note":"Sent"}
                                        """
                                                .formatted(payoutRef)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(get("/api/v1/orders/" + ord1Id + "/payments").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position.refundedPaid").value(400.00))
                .andExpect(jsonPath("$.position.netHeld").value(200.00))
                .andExpect(jsonPath("$.position.refundable").value(200.00))
                .andExpect(jsonPath("$.refunds.length()").value(1));

        // Cancelled order now has refundable=200 and no open request -> back in /refunds/due!
        mockMvc.perform(get("/api/v1/refunds/due").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.orderId=='" + ord1Id + "')].refundable").value(200.00));

        // Request remaining 200.00; owner exempt from four-eyes approves own request -> test CASH &
        // duplicate ref guard
        String rfd2Json =
                mockMvc.perform(
                                post("/api/v1/orders/" + ord1Id + "/refunds")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"amount\":200.00,\"reason\":\"ORDER_CANCELLED\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID rfd2Id = UUID.fromString(json.readTree(rfd2Json).get("id").asString());

        mockMvc.perform(
                        post("/api/v1/refunds/" + rfd2Id + "/approve")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // Reusing payoutRef across refunds -> 409 DUPLICATE_REFERENCE
        mockMvc.perform(
                        post("/api/v1/refunds/" + rfd2Id + "/pay")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"payoutMethod":"BKASH","payoutReference":"%s"}
                                        """
                                                .formatted(payoutRef)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.DUPLICATE_REFERENCE));

        // CASH payout requires note & succeeds -> netHeld = 0.00, due list empty for ord1Id
        mockMvc.perform(
                        post("/api/v1/refunds/" + rfd2Id + "/pay")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"payoutMethod\":\"CASH\",\"note\":\"Cash handover\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(
                        get("/api/v1/refunds")
                                .param("status", "PAID")
                                .param("orderNumber", ord1Number)
                                .cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/orders/" + ord1Id).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentPosition.netHeld").value(0.00))
                .andExpect(jsonPath("$.paymentPosition.refundDue").value(false));
    }

    @Test
    @DisplayName(
            "concurrency: simultaneous refund requests exceeding refundable, double approve, and double pay")
    void refundConcurrencyRaces() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("RFDRACE", "600.00", "250.00");
        addOpeningStock(whId, item.variant().getId(), 10);
        Cookie ownerCookie = signIn("owner");

        String ordBody = createPrepaidOrderAndGetBody(ownerCookie, item.variant().getSku());
        UUID ordId = UUID.fromString(json.readTree(ordBody).get("id").asString());
        UUID payId =
                recordPayment(
                        ownerCookie, ordId, "BKASH", "600.00", "RFDR" + shortId().toUpperCase());
        verifyPayment(ownerCookie, payId, "600.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Race 1: Two simultaneous refund requests of 400.00 against 600.00 refundable -> 1
            // succeeds (201), 1 fails (409 REFUND_EXCEEDS_PAID)
            CountDownLatch ready1 = new CountDownLatch(2);
            CountDownLatch go1 = new CountDownLatch(1);
            Future<Integer> fr1 =
                    pool.submit(
                            () -> {
                                ready1.countDown();
                                go1.await();
                                return mockMvc.perform(
                                                post("/api/v1/orders/" + ordId + "/refunds")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"amount\":400.00,\"reason\":\"CUSTOMER_REQUEST\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> fr2 =
                    pool.submit(
                            () -> {
                                ready1.countDown();
                                go1.await();
                                return mockMvc.perform(
                                                post("/api/v1/orders/" + ordId + "/refunds")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"amount\":400.00,\"reason\":\"CUSTOMER_REQUEST\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            ready1.await();
            go1.countDown();
            List<Integer> reqStatuses = List.of(fr1.get(), fr2.get());
            assertTrue(reqStatuses.contains(201));
            assertTrue(reqStatuses.contains(409));

            String listJson =
                    mockMvc.perform(
                                    get("/api/v1/orders/" + ordId + "/payments")
                                            .cookie(ownerCookie))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            UUID rfdId =
                    UUID.fromString(
                            json.readTree(listJson).get("refunds").get(0).get("id").asString());

            // Race 2: Double approve -> 1 success (200), 1 INVALID_TRANSITION (409), only 1
            // APPROVED row
            CountDownLatch ready2 = new CountDownLatch(2);
            CountDownLatch go2 = new CountDownLatch(1);
            Future<Integer> fa1 =
                    pool.submit(
                            () -> {
                                ready2.countDown();
                                go2.await();
                                return mockMvc.perform(
                                                post("/api/v1/refunds/" + rfdId + "/approve")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf()))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> fa2 =
                    pool.submit(
                            () -> {
                                ready2.countDown();
                                go2.await();
                                return mockMvc.perform(
                                                post("/api/v1/refunds/" + rfdId + "/approve")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf()))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            ready2.await();
            go2.countDown();
            List<Integer> appStatuses = List.of(fa1.get(), fa2.get());
            assertEquals(1, appStatuses.stream().filter(s -> s == 200).count());
            assertEquals(1, appStatuses.stream().filter(s -> s == 409).count());

            // Race 3: Double pay -> 1 success (200), 1 INVALID_TRANSITION (409), only 1 PAID row
            String payoutRef = "RFDRP" + shortId().toUpperCase();
            CountDownLatch ready3 = new CountDownLatch(2);
            CountDownLatch go3 = new CountDownLatch(1);
            Future<Integer> fp1 =
                    pool.submit(
                            () -> {
                                ready3.countDown();
                                go3.await();
                                return mockMvc.perform(
                                                post("/api/v1/refunds/" + rfdId + "/pay")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"payoutMethod\":\"NAGAD\",\"payoutReference\":\""
                                                                        + payoutRef
                                                                        + "\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> fp2 =
                    pool.submit(
                            () -> {
                                ready3.countDown();
                                go3.await();
                                return mockMvc.perform(
                                                post("/api/v1/refunds/" + rfdId + "/pay")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"payoutMethod\":\"NAGAD\",\"payoutReference\":\""
                                                                        + payoutRef
                                                                        + "\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            ready3.await();
            go3.countDown();
            List<Integer> payStatuses = List.of(fp1.get(), fp2.get());
            assertEquals(1, payStatuses.stream().filter(s -> s == 200).count());
            assertEquals(1, payStatuses.stream().filter(s -> s == 409).count());

            List<RefundEvent> events =
                    refundEventRepository.findByRefundIdOrderByCreatedAtAsc(rfdId);
            assertEquals(2, events.size()); // exactly 1 APPROVED + 1 PAID
        } finally {
            pool.shutdownNow();
        }
    }

    private void grantRefundApprove(String usernameLike, UUID whId) {
        UUID userId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM app_users WHERE username LIKE ? ORDER BY created_at DESC LIMIT 1",
                        UUID.class,
                        usernameLike);
        overrideRepository.saveAndFlush(
                new UserPermissionOverride(
                        UUID.randomUUID(),
                        userId,
                        "REFUND_APPROVE",
                        OverrideEffect.ALLOW,
                        whId,
                        demoOwner().getId(),
                        Instant.now()));
    }

    private String createPrepaidOrderAndGetBody(Cookie actor, String sku) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/orders/manual")
                                .cookie(actor)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "customerName": "Refund Customer",
                                          "phone": "01712345678",
                                          "address": "Banani",
                                          "lines": [{"sku": "%s", "quantity": 1}],
                                          "paymentMode": "FULL_PREPAID",
                                          "confirmNow": false
                                        }
                                        """
                                                .formatted(sku)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
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
                                                {"method":"%s","amount":"%s","reference":"%s"}
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
}
