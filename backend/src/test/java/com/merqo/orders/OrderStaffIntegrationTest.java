package com.merqo.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.merqo.common.error.ErrorCodes;
import com.merqo.common.time.BusinessTime;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.integration.IntegrationAuthFilter;
import com.merqo.inventory.StockBalance;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.orders.domain.RequestSignature;
import com.merqo.pos.PosIntegrationSupport;

/**
 * End-to-end integration tests for staff order handling: call logging, customer edits, atomic
 * confirm/reserve, decline, cancel/release, cancellation request, concurrency races, append-only
 * database triggers, Dhaka date filters, and reservation balance invariants.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderStaffIntegrationTest extends PosIntegrationSupport {

    @Autowired private ObjectMapper json;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private OrderCallLogRepository callLogRepository;
    @Autowired private OrderStatusHistoryRepository statusHistoryRepository;

    @Test
    @DisplayName(
            "call log, customer phone fix, all-or-nothing confirm, cancel release, and decline")
    void fullStaffWorkflowAndReservationLifecycle() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture shirtA = createProductFixture("STAFF-A", "500.00", "250.00");
        ProductFixture shirtB = createProductFixture("STAFF-B", "300.00", "150.00");
        addOpeningStock(whId, shirtA.variant().getId(), 5);
        // Only 1 unit of shirtB so a 2-unit order fails multi-line reservation atomically!
        addOpeningStock(whId, shirtB.variant().getId(), 1);

        Cookie ownerCookie = signIn("owner");
        ClientCreds client = createClient(ownerCookie, "Staff Flow Client");

        // Create order with bad phone + 2 units of shirtA + 2 units of shirtB
        String extId = "STF-" + shortId();
        String payload =
                """
                {
                  "externalOrderId": "%s",
                  "customerName": "Imran Khan",
                  "phone": "bad-phone-000",
                  "address": "Banani Road 11",
                  "lines": [
                    {"sku": "%s", "quantity": 2, "unitPrice": "500.00"},
                    {"sku": "%s", "quantity": 2, "unitPrice": "300.00"}
                  ],
                  "shippingFee": "0.00",
                  "discountTotal": "0.00",
                  "total": "1600.00",
                  "paymentMode": "ADVANCE_COD",
                  "advanceAmount": "300.00",
                  "codAmount": "1300.00",
                  "claimedAdvanceMethod": "BKASH",
                  "claimedAdvanceReference": "BK-CLAIM-1"
                }
                """
                        .formatted(extId, shirtA.variant().getSku(), shirtB.variant().getSku());

        String intakeBody =
                mockMvc.perform(signedPost("/integration/v1/orders", payload, client))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID orderId = UUID.fromString(json.readTree(intakeBody).get("orderId").asString());

        Cookie whStaff = signIn("orders");

        // Filter search + Dhaka business date filter
        LocalDate todayDhaka = BusinessTime.today(java.time.Clock.systemUTC());
        mockMvc.perform(
                        get("/api/v1/orders")
                                .param("q", extId)
                                .param("needsAction", "true")
                                .param("fromDate", todayDhaka.toString())
                                .param("toDate", todayDhaka.toString())
                                .cookie(whStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].flags.invalidPhone").value(true));

        // 1. NO_ANSWER without note -> 422 CALL_NOTE_REQUIRED
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/calls")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"result\":\"NO_ANSWER\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.CALL_NOTE_REQUIRED));

        // 2. NO_ANSWER with note -> moves to FOLLOW_UP, schedules followUpAt, attempt = 1
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/calls")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"result\":\"NO_ANSWER\",\"note\":\"Ringing twice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.followUpAt").isNotEmpty())
                .andExpect(jsonPath("$.callLogs[0].attemptNumber").value(1));

        // 3. Confirming with unresolved invalid phone -> 422 INVALID_PHONE
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/confirm")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_PHONE));

        // 4. Fix phone via editCustomer -> clears invalidPhone flag
        mockMvc.perform(
                        put("/api/v1/orders/" + orderId + "/customer")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "customerName": "Imran Khan",
                                          "phone": "+880 1755-667788",
                                          "address": "Banani Road 11, Dhaka",
                                          "district": "Dhaka"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNormalized").value("01755667788"))
                .andExpect(jsonPath("$.flags.invalidPhone").value(false));

        // 5. Confirm fails with 409 INSUFFICIENT_STOCK on line 2 (shirtB has 1 < 2), AND shirtA's
        // reservation must remain 0 (all-or-nothing rollback!)
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/confirm")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INSUFFICIENT_STOCK));

        StockBalance shirtABefore = inventoryService.balanceOf(whId, shirtA.variant().getId());
        assertEquals(0, shirtABefore.getReserved());

        // Receive 1 more unit of shirtB -> confirm now succeeds!
        addOpeningStock(whId, shirtB.variant().getId(), 1);
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/confirm")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"Verified over phone\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        assertEquals(2, inventoryService.balanceOf(whId, shirtA.variant().getId()).getReserved());
        assertEquals(2, inventoryService.balanceOf(whId, shirtB.variant().getId()).getReserved());

        // Double confirm -> 409 INVALID_TRANSITION
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/confirm")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_TRANSITION));

        // Record cancellation request flag on CONFIRMED order
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/cancellation-request")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"Customer texted to cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags.cancellationRequested").value(true));

        // Cancel requires ORDER_CANCEL (owner/manager, or user with ORDER_CANCEL): owner cancels
        // and releases both line reservations + surfaces advanceClaimed notice
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/cancel")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"OTHER\",\"note\":\"Requested via WhatsApp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("CANCELLED"))
                .andExpect(jsonPath("$.releasedReservation").value(true))
                .andExpect(jsonPath("$.customerContacted").value(true))
                .andExpect(jsonPath("$.advanceClaimed").value(true));

        assertEquals(0, inventoryService.balanceOf(whId, shirtA.variant().getId()).getReserved());
        assertEquals(0, inventoryService.balanceOf(whId, shirtB.variant().getId()).getReserved());

        // Double cancel -> 409 INVALID_TRANSITION
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/cancel")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"CUSTOMER_REQUESTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_TRANSITION));

        // Decline workflow: OTHER without note -> 422 REASON_REQUIRED; valid decline -> DECLINED
        UUID unconfirmedId =
                submitQuickCodOrder(client, shirtA.variant().getSku(), "01812345678", "500.00");
        mockMvc.perform(
                        post("/api/v1/orders/" + unconfirmedId + "/decline")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.REASON_REQUIRED));

        mockMvc.perform(
                        post("/api/v1/orders/" + unconfirmedId + "/decline")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"CUSTOMER_DECLINED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        // Verify warehouse balance invariants across all warehouse rows
        assertReservationInvariants(whId);
    }

    @Test
    @DisplayName("concurrent confirm race on last unit lets one win and rejects the other cleanly")
    void concurrentConfirmOnLastUnitAndAppendOnlyTriggers() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture rare = createProductFixture("RACE", "450.00", "200.00");
        addOpeningStock(whId, rare.variant().getId(), 1);

        Cookie ownerCookie = signIn("owner");
        ClientCreds client = createClient(ownerCookie, "Concurrency Confirm Client");
        UUID order1 = submitQuickCodOrder(client, rare.variant().getSku(), "01711112222", "450.00");
        UUID order2 = submitQuickCodOrder(client, rare.variant().getSku(), "01711113333", "450.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Integer> c1 =
                    () -> {
                        ready.countDown();
                        go.await();
                        return mockMvc.perform(
                                        post("/api/v1/orders/" + order1 + "/confirm")
                                                .cookie(ownerCookie)
                                                .with(CsrfTestSupport.csrf()))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            Callable<Integer> c2 =
                    () -> {
                        ready.countDown();
                        go.await();
                        return mockMvc.perform(
                                        post("/api/v1/orders/" + order2 + "/confirm")
                                                .cookie(ownerCookie)
                                                .with(CsrfTestSupport.csrf()))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            Future<Integer> f1 = pool.submit(c1);
            Future<Integer> f2 = pool.submit(c2);
            ready.await();
            go.countDown();

            List<Integer> statuses = List.of(f1.get(), f2.get());
            assertTrue(statuses.contains(200), "One confirmation must succeed: " + statuses);
            assertTrue(
                    statuses.contains(409),
                    "Competing confirmation must fail with 409 INSUFFICIENT_STOCK: " + statuses);
        } finally {
            pool.shutdownNow();
        }

        StockBalance balance = inventoryService.balanceOf(whId, rare.variant().getId());
        assertEquals(1, balance.getReserved());
        assertTrue(balance.getReserved() <= balance.getOnHand());

        // Test append-only database triggers directly via JdbcTemplate
        UUID winningOrderId =
                orderRepository.findById(order1).orElseThrow().getStatus() == OrderStatus.CONFIRMED
                        ? order1
                        : order2;
        List<OrderStatusHistory> hist =
                statusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(winningOrderId);
        assertFalse(hist.isEmpty());
        UUID histId = hist.getFirst().getId();

        assertThrows(
                DataAccessException.class,
                () ->
                        jdbcTemplate.update(
                                "UPDATE order_status_history SET reason = 'TAMPERED' WHERE id = ?",
                                histId));
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update("DELETE FROM order_status_history WHERE id = ?", histId));

        mockMvc.perform(
                        post("/api/v1/orders/" + winningOrderId + "/calls")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"result\":\"ANSWERED\",\"note\":\"Confirmed\"}"))
                .andExpect(
                        status().isConflict()); // CONFIRMED cannot log call; test on loser instead

        UUID loserOrderId = winningOrderId.equals(order1) ? order2 : order1;
        mockMvc.perform(
                        post("/api/v1/orders/" + loserOrderId + "/calls")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"result\":\"ANSWERED\",\"note\":\"Check stock\"}"))
                .andExpect(status().isOk());
        UUID callId =
                callLogRepository
                        .findByOrderIdOrderByAttemptNumberAsc(loserOrderId)
                        .getFirst()
                        .getId();
        assertThrows(
                DataAccessException.class,
                () ->
                        jdbcTemplate.update(
                                "UPDATE order_call_logs SET note = 'TAMPERED' WHERE id = ?",
                                callId));
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update("DELETE FROM order_call_logs WHERE id = ?", callId));

        assertReservationInvariants(whId);
    }

    private void assertReservationInvariants(UUID whId) {
        Integer invalidReserved =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inventory_balances WHERE location_id = ? AND (reserved"
                                + " < 0 OR reserved > on_hand)",
                        Integer.class,
                        whId);
        assertEquals(0, invalidReserved);

        Integer balanceTotalReserved =
                jdbcTemplate.queryForObject(
                        "SELECT coalesce(sum(reserved), 0) FROM inventory_balances WHERE"
                                + " location_id = ?",
                        Integer.class,
                        whId);
        Integer confirmedLinesSum =
                jdbcTemplate.queryForObject(
                        """
                        SELECT coalesce(sum(ol.quantity), 0)
                        FROM order_lines ol
                        JOIN orders o ON o.id = ol.order_id
                        WHERE o.location_id = ? AND o.status = 'CONFIRMED'
                        """,
                        Integer.class,
                        whId);
        // Reserved balance must be at least sum of active CONFIRMED order lines (equals it when no
        // transfer reservations are pending).
        assertTrue(balanceTotalReserved >= confirmedLinesSum);
    }

    private UUID submitQuickCodOrder(ClientCreds client, String sku, String phone, String unitPrice)
            throws Exception {
        String ext = "Q-" + shortId();
        String body =
                """
                {
                  "externalOrderId": "%s",
                  "customerName": "Quick Buyer",
                  "phone": "%s",
                  "address": "Gulshan 2, Dhaka",
                  "lines": [{"sku": "%s", "quantity": 1, "unitPrice": "%s"}],
                  "shippingFee": "0.00",
                  "discountTotal": "0.00",
                  "total": "%s",
                  "paymentMode": "COD",
                  "advanceAmount": "0.00",
                  "codAmount": "%s"
                }
                """
                        .formatted(ext, phone, sku, unitPrice, unitPrice, unitPrice);
        String resp =
                mockMvc.perform(signedPost("/integration/v1/orders", body, client))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(json.readTree(resp).get("orderId").asString());
    }

    private ClientCreds createClient(Cookie ownerCookie, String name) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/integration/clients")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"" + name + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode root = json.readTree(body);
        return new ClientCreds(
                UUID.fromString(root.get("client").get("id").asString()),
                root.get("client").get("keyId").asString(),
                root.get("secret").asString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedPost(
            String uri, String payload, ClientCreds client) {
        long now = Instant.now().getEpochSecond();
        return post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                .header(IntegrationAuthFilter.HEADER_TIMESTAMP, String.valueOf(now))
                .header(
                        IntegrationAuthFilter.HEADER_SIGNATURE,
                        RequestSignature.computeHex(client.secret(), now, payload));
    }

    private record ClientCreds(UUID id, String keyId, String secret) {}
}
