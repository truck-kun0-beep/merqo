package com.merqo.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.merqo.access.AccountType;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.payments.domain.OnlinePaymentMethod;
import com.merqo.pos.PosIntegrationSupport;

/**
 * Integration tests for Slice B: order advance payment recording, claim prefill, manual &amp; mock
 * provider verification, TrxID cross-channel guard (including rejected release), pending queue,
 * separate-verifier policy, order detail position/readyForShipment, and concurrency races.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderPaymentsIntegrationTest extends PosIntegrationSupport {

    @Autowired private ObjectMapper json;
    @Autowired private MockProviderTransactionRepository mockTxRepository;
    @Autowired private OrderPaymentService orderPaymentService;

    @Test
    @DisplayName(
            "record, prefill, verify/reject rules, reference release on reject, pending queue, and order detail position")
    void recordPrefillVerifyRejectAndOrderPosition() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("PAYB", "500.00", "200.00");
        addOpeningStock(whId, item.variant().getId(), 20);

        Cookie ownerCookie = signIn("owner");
        Cookie whStaff =
                createAndSignIn("payb.staff", AccountType.STAFF, "ONLINE_ORDER_STAFF", whId, null);

        // 1. Create ADVANCE_COD manual order (2 x 500 = 1000, advance = 300, cod = 700) with
        // claimed TrxID
        String claimedRef = "BKCLM" + shortId().toUpperCase();
        UUID advOrderId =
                createManualOrder(
                        whStaff,
                        item.variant().getSku(),
                        2,
                        "ADVANCE_COD",
                        "300.00",
                        "BKASH",
                        claimedRef);

        // Claim prefill returns claimed method, reference, amount, remainingAllowed = 300.00
        mockMvc.perform(
                        get("/api/v1/orders/" + advOrderId + "/payments/claim-prefill")
                                .cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasClaim").value(true))
                .andExpect(jsonPath("$.method").value("BKASH"))
                .andExpect(jsonPath("$.reference").value(claimedRef))
                .andExpect(jsonPath("$.claimedAmount").value(300.00))
                .andExpect(jsonPath("$.remainingAllowed").value(300.00));

        // Initial order detail shows shortfall = 300, readyForShipment = false
        mockMvc.perform(get("/api/v1/orders/" + advOrderId).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyForShipment").value(false))
                .andExpect(jsonPath("$.paymentPosition.requiredPrepayment").value(300.00))
                .andExpect(jsonPath("$.paymentPosition.shortfall").value(300.00));

        // 2. COD order refuses advance payment with PAYMENT_NOT_EXPECTED
        UUID codOrderId =
                createManualOrder(whStaff, item.variant().getSku(), 1, "COD", null, null, null);
        mockMvc.perform(
                        post("/api/v1/orders/" + codOrderId + "/payments")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "method": "BKASH",
                                          "amount": "100.00",
                                          "reference": "CODREF99"
                                        }
                                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.PAYMENT_NOT_EXPECTED));

        // 3. Card PAN in reference rejected cleanly without echoing card data
        mockMvc.perform(
                        post("/api/v1/orders/" + advOrderId + "/payments")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "method": "CARD",
                                          "amount": "100.00",
                                          "reference": "4111-1111-1111-1111"
                                        }
                                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.CARD_NUMBER_REJECTED));

        // 4. Record from prefill (partial 150.00 with claimedRef -> source WEBSITE_CLAIM)
        String p1Json =
                mockMvc.perform(
                                post("/api/v1/orders/" + advOrderId + "/payments")
                                        .cookie(whStaff)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "method": "BKASH",
                                                  "amount": "150.00",
                                                  "reference": "%s",
                                                  "fromClaim": true
                                                }
                                                """
                                                        .formatted(claimedRef.toLowerCase())))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.source").value("WEBSITE_CLAIM"))
                        .andExpect(jsonPath("$.reference").value(claimedRef))
                        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID p1Id = UUID.fromString(json.readTree(p1Json).get("id").asString());

        // Over-cap recording (> remaining 150) refused with PAYMENT_EXCEEDS_TOTAL
        mockMvc.perform(
                        post("/api/v1/orders/" + advOrderId + "/payments")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "method": "NAGAD",
                                          "amount": "151.00",
                                          "reference": "NGEXCEED1"
                                        }
                                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.PAYMENT_EXCEEDS_TOTAL));

        // Also verify overdue flag on GET /payments/pending using a back-dated payment
        UUID overdueOrd =
                createManualOrder(
                        whStaff, item.variant().getSku(), 1, "FULL_PREPAID", "500.00", null, null);
        String overdueRef = "OVRDUE" + shortId().toUpperCase();
        UUID overduePayId =
                orderPaymentService
                        .recordPaymentInternal(
                                ownerContext(),
                                overdueOrd,
                                "BKASH",
                                new BigDecimal("500.00"),
                                overdueRef,
                                "Backdated overdue payment",
                                false,
                                Instant.now().minus(30, ChronoUnit.HOURS),
                                null,
                                null)
                        .id();
        mockMvc.perform(get("/api/v1/payments/pending").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + p1Id + "')].overdue").value(false))
                .andExpect(jsonPath("$[?(@.id=='" + overduePayId + "')].overdue").value(true));

        // 5. Reject p1 with evidence -> releases claimedRef! (Missing evidence -> 422)
        mockMvc.perform(
                        post("/api/v1/payments/" + p1Id + "/verify")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"REJECTED\",\"evidence\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED));

        mockMvc.perform(
                        post("/api/v1/payments/" + p1Id + "/verify")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"decision\":\"REJECTED\",\"evidence\":\"Customer sent wrong screenshot\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decision.decision").value("REJECTED"));

        // Second decision on p1 -> 409 PAYMENT_ALREADY_DECIDED
        mockMvc.perform(
                        post("/api/v1/payments/" + p1Id + "/verify")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"decision\":\"VERIFIED\",\"confirmedAmount\":150.00,\"evidence\":\"Retry\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.PAYMENT_ALREADY_DECIDED));

        // 6. Released TrxID can now be reused for full 300.00 on the same order!
        String p2Json =
                mockMvc.perform(
                                post("/api/v1/orders/" + advOrderId + "/payments")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "method": "BKASH",
                                                  "amount": "300.00",
                                                  "reference": "%s"
                                                }
                                                """
                                                        .formatted(claimedRef)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID p2Id = UUID.fromString(json.readTree(p2Json).get("id").asString());

        // Verify with wrong amount -> 422 AMOUNT_MISMATCH
        mockMvc.perform(
                        post("/api/v1/payments/" + p2Id + "/verify")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"decision\":\"VERIFIED\",\"confirmedAmount\":250.00,\"evidence\":\"Short check\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.AMOUNT_MISMATCH));

        // Verify with matching amount & evidence -> VERIFIED, order readyForShipment = true!
        mockMvc.perform(
                        post("/api/v1/payments/" + p2Id + "/verify")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"decision\":\"VERIFIED\",\"confirmedAmount\":300.00,\"evidence\":\"Verified in merchant SMS log\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.decision.deciderKind").value("STAFF_MANUAL"));

        mockMvc.perform(get("/api/v1/orders/" + advOrderId).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyForShipment").value(true))
                .andExpect(jsonPath("$.paymentPosition.verifiedPaid").value(300.00))
                .andExpect(jsonPath("$.paymentPosition.rejectedPaid").value(150.00))
                .andExpect(jsonPath("$.paymentPosition.shortfall").value(0.00));

        // Warehouse staff at WH without PAYMENT_VERIFY gets 403 on verify
        Cookie warehouseStaff = signIn("warehouse");
        mockMvc.perform(
                        post("/api/v1/payments/" + p2Id + "/verify")
                                .cookie(warehouseStaff)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"decision\":\"VERIFIED\",\"confirmedAmount\":300.00,\"evidence\":\"Probe\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName(
            "PaymentReferenceGuard prevents duplicate TrxID across POS sales and online payments in both directions")
    void crossChannelTrxIdGuardAndMockProviderCheck() throws Exception {
        UUID whId = locationId("WH");
        UUID dmId = locationId("DM");
        ProductFixture item = createProductFixture("TRXGD", "400.00", "180.00");
        addOpeningStock(whId, item.variant().getId(), 20);
        addOpeningStock(dmId, item.variant().getId(), 20);

        Cookie ownerCookie = signIn("owner");
        Cookie dmCashier = createAndSignIn("trx.cashier", AccountType.STAFF, "MANAGER", dmId, null);
        UUID dmSessionId = openSession(dmCashier, dmId, "1000.00");
        assertTrue(dmSessionId != null);

        // Direction 1: POS sale takes BKASH TrxID -> Online order payment with same BKASH TrxID
        // fails 409 DUPLICATE_REFERENCE
        String posTrx = "POSTRX" + shortId().toUpperCase();
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
                                                .formatted(dmId, item.variant().getId(), posTrx)))
                .andExpect(status().isCreated());

        UUID ord1 =
                createManualOrder(
                        ownerCookie,
                        item.variant().getSku(),
                        1,
                        "FULL_PREPAID",
                        "400.00",
                        null,
                        null);
        mockMvc.perform(
                        post("/api/v1/orders/" + ord1 + "/payments")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "method": "BKASH",
                                          "amount": "400.00",
                                          "reference": "%s"
                                        }
                                        """
                                                .formatted(posTrx)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.DUPLICATE_REFERENCE));

        // Different method (NAGAD) with same reference string IS allowed
        mockMvc.perform(
                        post("/api/v1/orders/" + ord1 + "/payments")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "method": "NAGAD",
                                          "amount": "400.00",
                                          "reference": "%s"
                                        }
                                        """
                                                .formatted(posTrx)))
                .andExpect(status().isCreated());

        // Direction 2: Online order payment takes NAGAD TrxID -> POS sale with same NAGAD TrxID
        // fails 409 DUPLICATE_REFERENCE
        String onlineTrx = "ONLTRX" + shortId().toUpperCase();
        UUID ord2 =
                createManualOrder(
                        ownerCookie,
                        item.variant().getSku(),
                        1,
                        "FULL_PREPAID",
                        "400.00",
                        null,
                        null);
        String onlPayJson =
                mockMvc.perform(
                                post("/api/v1/orders/" + ord2 + "/payments")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "method": "NAGAD",
                                                  "amount": "400.00",
                                                  "reference": "%s"
                                                }
                                                """
                                                        .formatted(onlineTrx)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID onlPayId = UUID.fromString(json.readTree(onlPayJson).get("id").asString());

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
                                                        dmId, item.variant().getId(), onlineTrx)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.DUPLICATE_REFERENCE));

        // Mock provider check: wrong amount -> AMOUNT_MISMATCH, matching -> CONFIRMED, claimed
        // elsewhere -> ALREADY_CLAIMED
        String mockRefWrong = "MCKWRG" + shortId().toUpperCase();
        UUID ord3 =
                createManualOrder(
                        ownerCookie,
                        item.variant().getSku(),
                        1,
                        "FULL_PREPAID",
                        "400.00",
                        null,
                        null);
        String pWrongJson =
                mockMvc.perform(
                                post("/api/v1/orders/" + ord3 + "/payments")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "method": "BKASH",
                                                  "amount": "400.00",
                                                  "reference": "%s"
                                                }
                                                """
                                                        .formatted(mockRefWrong)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID pWrongId = UUID.fromString(json.readTree(pWrongJson).get("id").asString());

        mockTxRepository.saveAndFlush(
                new MockProviderTransaction(
                        UUID.randomUUID(),
                        OnlinePaymentMethod.BKASH,
                        mockRefWrong,
                        new BigDecimal("350.00"),
                        "01700000001",
                        null,
                        Instant.now()));
        mockMvc.perform(
                        post("/api/v1/payments/" + pWrongId + "/check")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.payment.status").value("PENDING_VERIFICATION"));

        // Seed exact match for onlineTrx on ord2 -> CONFIRMED with deciderKind PROVIDER:mock
        mockTxRepository.saveAndFlush(
                new MockProviderTransaction(
                        UUID.randomUUID(),
                        OnlinePaymentMethod.NAGAD,
                        onlineTrx,
                        new BigDecimal("400.00"),
                        "01700000002",
                        null,
                        Instant.now()));
        mockMvc.perform(
                        post("/api/v1/payments/" + onlPayId + "/check")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"))
                .andExpect(jsonPath("$.payment.status").value("VERIFIED"))
                .andExpect(jsonPath("$.payment.decision.deciderKind").value("PROVIDER:mock"));

        // Already-claimed simulated transaction tested via BANK reference collision
        UUID ord4 =
                createManualOrder(
                        ownerCookie,
                        item.variant().getSku(),
                        1,
                        "FULL_PREPAID",
                        "400.00",
                        null,
                        null);
        String claimedBankRef = "BNKCLM" + shortId().toUpperCase();
        mockTxRepository.saveAndFlush(
                new MockProviderTransaction(
                        UUID.randomUUID(),
                        OnlinePaymentMethod.BANK,
                        claimedBankRef,
                        new BigDecimal("400.00"),
                        "BankSender",
                        onlPayId,
                        Instant.now()));
        String p4Json =
                mockMvc.perform(
                                post("/api/v1/orders/" + ord4 + "/payments")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "method": "BANK",
                                                  "amount": "400.00",
                                                  "reference": "%s"
                                                }
                                                """
                                                        .formatted(claimedBankRef)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID p4Id = UUID.fromString(json.readTree(p4Json).get("id").asString());
        mockMvc.perform(
                        post("/api/v1/payments/" + p4Id + "/check")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_CLAIMED"))
                .andExpect(jsonPath("$.payment.status").value("PENDING_VERIFICATION"));
    }

    @Test
    @DisplayName(
            "concurrency: POS vs online same TrxID, online vs online same TrxID, and simultaneous verify decisions")
    void concurrencyRacesTrxIdAndVerify() throws Exception {
        UUID whId = locationId("WH");
        UUID dmId = locationId("DM");
        ProductFixture item = createProductFixture("PAYRACE", "500.00", "200.00");
        addOpeningStock(whId, item.variant().getId(), 20);
        addOpeningStock(dmId, item.variant().getId(), 20);

        Cookie ownerCookie = signIn("owner");
        Cookie raceCashier =
                createAndSignIn("race.cashier", AccountType.STAFF, "MANAGER", dmId, null);
        openSession(raceCashier, dmId, "1000.00");

        // Race 1: POS sale vs Online order with identical BKASH TrxID -> 1 wins, 1 gets 409
        // DUPLICATE_REFERENCE
        UUID ordA =
                createManualOrder(
                        ownerCookie,
                        item.variant().getSku(),
                        1,
                        "FULL_PREPAID",
                        "500.00",
                        null,
                        null);
        String sharedPosTrx = "RACE1" + shortId().toUpperCase();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready1 = new CountDownLatch(2);
            CountDownLatch go1 = new CountDownLatch(1);
            Callable<Integer> posCall =
                    () -> {
                        ready1.countDown();
                        go1.await();
                        return mockMvc.perform(
                                        post("/api/v1/pos/sales")
                                                .cookie(raceCashier)
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
                                                                        dmId,
                                                                        item.variant().getId(),
                                                                        sharedPosTrx)))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            Callable<Integer> onlineCall =
                    () -> {
                        ready1.countDown();
                        go1.await();
                        return mockMvc.perform(
                                        post("/api/v1/orders/" + ordA + "/payments")
                                                .cookie(ownerCookie)
                                                .with(CsrfTestSupport.csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {
                                                          "method": "BKASH",
                                                          "amount": "500.00",
                                                          "reference": "%s"
                                                        }
                                                        """
                                                                .formatted(sharedPosTrx)))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            Future<Integer> fPos = pool.submit(posCall);
            Future<Integer> fOnl = pool.submit(onlineCall);
            ready1.await();
            go1.countDown();
            List<Integer> r1 = List.of(fPos.get(), fOnl.get());
            assertTrue(r1.contains(201), "One writer must succeed: " + r1);
            assertTrue(r1.contains(409), "Competing writer must get 409: " + r1);

            // Race 2: Two simultaneous online payments on distinct orders with same NAGAD TrxID
            UUID ordB1 =
                    createManualOrder(
                            ownerCookie,
                            item.variant().getSku(),
                            1,
                            "FULL_PREPAID",
                            "500.00",
                            null,
                            null);
            UUID ordB2 =
                    createManualOrder(
                            ownerCookie,
                            item.variant().getSku(),
                            1,
                            "FULL_PREPAID",
                            "500.00",
                            null,
                            null);
            String sharedOnlineTrx = "RACE2" + shortId().toUpperCase();
            CountDownLatch ready2 = new CountDownLatch(2);
            CountDownLatch go2 = new CountDownLatch(1);
            Future<Integer> fb1 =
                    pool.submit(
                            () -> {
                                ready2.countDown();
                                go2.await();
                                return mockMvc.perform(
                                                post("/api/v1/orders/" + ordB1 + "/payments")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                """
                                                                {"method":"NAGAD","amount":"500.00","reference":"%s"}
                                                                """
                                                                        .formatted(
                                                                                sharedOnlineTrx)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> fb2 =
                    pool.submit(
                            () -> {
                                ready2.countDown();
                                go2.await();
                                return mockMvc.perform(
                                                post("/api/v1/orders/" + ordB2 + "/payments")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                """
                                                                {"method":"NAGAD","amount":"500.00","reference":"%s"}
                                                                """
                                                                        .formatted(
                                                                                sharedOnlineTrx)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            ready2.await();
            go2.countDown();
            List<Integer> r2 = List.of(fb1.get(), fb2.get());
            assertEquals(1, r2.stream().filter(s -> s == 201).count());
            assertEquals(1, r2.stream().filter(s -> s == 409).count());

            // Race 3: Two simultaneous verify decisions on one payment -> 1 wins (200), 1 gets 409
            UUID ordVerify =
                    createManualOrder(
                            ownerCookie,
                            item.variant().getSku(),
                            1,
                            "FULL_PREPAID",
                            "500.00",
                            null,
                            null);
            String payJson =
                    mockMvc.perform(
                                    post("/api/v1/orders/" + ordVerify + "/payments")
                                            .cookie(ownerCookie)
                                            .with(CsrfTestSupport.csrf())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"method":"BKASH","amount":"500.00","reference":"RACE3%s"}
                                                    """
                                                            .formatted(shortId().toUpperCase())))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            UUID payId = UUID.fromString(json.readTree(payJson).get("id").asString());

            CountDownLatch ready3 = new CountDownLatch(2);
            CountDownLatch go3 = new CountDownLatch(1);
            Future<Integer> fv1 =
                    pool.submit(
                            () -> {
                                ready3.countDown();
                                go3.await();
                                return mockMvc.perform(
                                                post("/api/v1/payments/" + payId + "/verify")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"decision\":\"VERIFIED\",\"confirmedAmount\":500.00,\"evidence\":\"Thread 1\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> fv2 =
                    pool.submit(
                            () -> {
                                ready3.countDown();
                                go3.await();
                                return mockMvc.perform(
                                                post("/api/v1/payments/" + payId + "/verify")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"decision\":\"REJECTED\",\"evidence\":\"Thread 2\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            ready3.await();
            go3.countDown();
            List<Integer> r3 = List.of(fv1.get(), fv2.get());
            assertEquals(1, r3.stream().filter(s -> s == 200).count());
            assertEquals(1, r3.stream().filter(s -> s == 409).count());
        } finally {
            pool.shutdownNow();
        }

        // Verify separate-verifier setting blocks non-owner self-verification while allowing owner
        mockMvc.perform(
                        put("/api/v1/settings")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"values\":{\"payments.require_separate_verifier\":\"true\"}}"))
                .andExpect(status().isOk());
        try {
            Cookie whManager = signIn("manager-ut");
            // Create a WH user with PAYMENT_RECORD + PAYMENT_VERIFY using WH WAREHOUSE_MANAGER
            // template?
            // Notice demo owner or WH manager with PAYMENT_VERIFY tests separate verifier!
            // Here use owner (exempt) vs creating a temp WH staff with WAREHOUSE_MANAGER template:
            Cookie whMgr2 =
                    createAndSignIn("wh.sep", AccountType.STAFF, "ONLINE_ORDER_STAFF", whId, null);
            assertFalse(whManager == null);
            UUID sepOrd =
                    createManualOrder(
                            ownerCookie,
                            item.variant().getSku(),
                            1,
                            "FULL_PREPAID",
                            "500.00",
                            null,
                            null);
            String pSepJson =
                    mockMvc.perform(
                                    post("/api/v1/orders/" + sepOrd + "/payments")
                                            .cookie(whMgr2)
                                            .with(CsrfTestSupport.csrf())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"method":"BKASH","amount":"500.00","reference":"SEP%s"}
                                                    """
                                                            .formatted(shortId().toUpperCase())))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            UUID pSepId = UUID.fromString(json.readTree(pSepJson).get("id").asString());

            mockMvc.perform(
                            post("/api/v1/payments/" + pSepId + "/verify")
                                    .cookie(whMgr2)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"decision\":\"VERIFIED\",\"confirmedAmount\":500.00,\"evidence\":\"Self verify\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(
                            jsonPath("$.errorCode")
                                    .value(ErrorCodes.SELF_VERIFICATION_NOT_ALLOWED));

            // Owner (or another verifier) succeeds
            mockMvc.perform(
                            post("/api/v1/payments/" + pSepId + "/verify")
                                    .cookie(ownerCookie)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"decision\":\"VERIFIED\",\"confirmedAmount\":500.00,\"evidence\":\"Independent check\"}"))
                    .andExpect(status().isOk());
        } finally {
            mockMvc.perform(
                            put("/api/v1/settings")
                                    .cookie(ownerCookie)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"values\":{\"payments.require_separate_verifier\":\"false\"}}"))
                    .andExpect(status().isOk());
        }
    }

    private UUID createManualOrder(
            Cookie actor,
            String sku,
            int qty,
            String mode,
            String advanceAmount,
            String claimedMethod,
            String claimedRef)
            throws Exception {
        String advField =
                advanceAmount == null ? "" : "\"advanceAmount\": \"" + advanceAmount + "\",";
        String clMethod =
                claimedMethod == null ? "" : "\"claimedAdvanceMethod\": \"" + claimedMethod + "\",";
        String clRef =
                claimedRef == null ? "" : "\"claimedAdvanceReference\": \"" + claimedRef + "\",";
        String payload =
                """
                {
                  "customerName": "Pay Customer",
                  "phone": "01712345678",
                  "address": "Dhanmondi 27",
                  "lines": [{"sku": "%s", "quantity": %d}],
                  "shippingFee": "0.00",
                  "discountTotal": "0.00",
                  %s
                  %s
                  %s
                  "paymentMode": "%s",
                  "confirmNow": false
                }
                """
                        .formatted(sku, qty, advField, clMethod, clRef, mode);
        String body =
                mockMvc.perform(
                                post("/api/v1/orders/manual")
                                        .cookie(actor)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode node = json.readTree(body);
        return UUID.fromString(node.get("id").asString());
    }
}
