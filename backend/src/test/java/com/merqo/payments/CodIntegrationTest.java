package com.merqo.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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

import com.merqo.common.error.ErrorCodes;
import com.merqo.common.time.BusinessTime;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.pos.PosIntegrationSupport;

/**
 * Integration tests for Slice D: COD collections, all-or-nothing courier settlements (exact, SHORT,
 * OVER), per-line fees, duplicate references, outstanding/gap calculations, and concurrency races.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CodIntegrationTest extends PosIntegrationSupport {

    @Autowired private ObjectMapper json;

    @Test
    @DisplayName(
            "COD collections, all-or-nothing settlement rollback, exact/SHORT/OVER, gap flag, and duplicate ref")
    void codCollectionSettlementAndGapLifecycle() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("CODX", "1000.00", "400.00");
        addOpeningStock(whId, item.variant().getId(), 20);
        Cookie ownerCookie = signIn("owner");

        // Unconfirmed COD order -> collection refused with 409 INVALID_TRANSITION
        UUID unconfirmedId = createOrder(ownerCookie, item.variant().getSku(), "COD", null, false);
        mockMvc.perform(
                        post("/api/v1/orders/" + unconfirmedId + "/cod-collections")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"courier\":\"PATHAO\",\"amount\":1000.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.INVALID_TRANSITION));

        // Confirmed FULL_PREPAID order -> COD_NOT_EXPECTED
        UUID prepaidId =
                createOrder(ownerCookie, item.variant().getSku(), "FULL_PREPAID", "1000.00", true);
        mockMvc.perform(
                        post("/api/v1/orders/" + prepaidId + "/cod-collections")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"courier\":\"PATHAO\",\"amount\":100.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.COD_NOT_EXPECTED));

        // Two confirmed COD orders (1000.00 each)
        UUID ord1 = createOrder(ownerCookie, item.variant().getSku(), "COD", null, true);
        String ord1Number =
                json.readTree(
                                mockMvc.perform(get("/api/v1/orders/" + ord1).cookie(ownerCookie))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("orderNumber")
                        .asString();
        UUID ord2 = createOrder(ownerCookie, item.variant().getSku(), "COD", null, true);

        LocalDate todayDhaka = BusinessTime.today(Clock.systemUTC());
        LocalDate oldDate = todayDhaka.minusDays(8);

        // Collect 1000 on ord1 (old date -> gap = true) and 600 on ord2
        mockMvc.perform(
                        post("/api/v1/orders/" + ord1 + "/cod-collections")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"courier":"PATHAO","amount":1000.00,"collectedOn":"%s","note":"Old pickup"}
                                        """
                                                .formatted(oldDate)))
                .andExpect(status().isCreated());

        // Over-collecting ord1 -> 409 COD_EXCEEDS_EXPECTED
        mockMvc.perform(
                        post("/api/v1/orders/" + ord1 + "/cod-collections")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"courier\":\"PATHAO\",\"amount\":1.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.COD_EXCEEDS_EXPECTED));

        mockMvc.perform(
                        post("/api/v1/orders/" + ord2 + "/cod-collections")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"courier":"STEADFAST","amount":600.00,"collectedOn":"%s"}
                                        """
                                                .formatted(todayDhaka)))
                .andExpect(status().isCreated());

        // Outstanding shows ord1 gap = true and ord2 uncollected = 400.00
        mockMvc.perform(get("/api/v1/cod/outstanding").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[?(@.orderId=='" + ord1 + "')].gap").value(true))
                .andExpect(
                        jsonPath("$.orders[?(@.orderId=='" + ord2 + "')].withCourier")
                                .value(600.00))
                .andExpect(
                        jsonPath("$.orders[?(@.orderId=='" + ord2 + "')].uncollected")
                                .value(400.00));

        // All-or-nothing rollback: line 1 valid (ord1 = 1000), line 2 exceeds ord2's withCourier
        // (601 > 600)
        String badRef = "BADSTL-" + shortId().toUpperCase();
        mockMvc.perform(
                        post("/api/v1/cod/settlements")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "courier": "PATHAO",
                                          "reference": "%s",
                                          "receivedOn": "%s",
                                          "receiveMethod": "BANK",
                                          "amountReceived": 1560.00,
                                          "lines": [
                                            {"orderId": "%s", "codCollectedAmount": 1000.00, "fee": 20.00},
                                            {"orderId": "%s", "codCollectedAmount": 601.00, "fee": 20.00}
                                          ]
                                        }
                                        """
                                                .formatted(badRef, todayDhaka, ord1, ord2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.COD_EXCEEDS_EXPECTED));

        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM courier_settlements WHERE reference = ?",
                        Integer.class,
                        badRef));

        // Mismatch without note -> 422 SETTLEMENT_MISMATCH
        String okRef = "PTHSTL-" + shortId().toUpperCase();
        mockMvc.perform(
                        post("/api/v1/cod/settlements")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "courier": "PATHAO",
                                          "reference": "%s",
                                          "receivedOn": "%s",
                                          "receiveMethod": "BANK",
                                          "amountReceived": 950.00,
                                          "lines": [
                                            {"orderNumber": "%s", "codCollectedAmount": 1000.00, "fee": 30.00}
                                          ]
                                        }
                                        """
                                                .formatted(okRef, todayDhaka, ord1Number)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.SETTLEMENT_MISMATCH));

        // Record SHORT settlement with note using orderNumber lookup! net = 970, received = 950,
        // diff = -20
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
                                                  "reference": "%s",
                                                  "receivedOn": "%s",
                                                  "receiveMethod": "BANK",
                                                  "amountReceived": 950.00,
                                                  "note": "Short remittance pending ticket",
                                                  "lines": [
                                                    {"orderNumber": "%s", "codCollectedAmount": 1000.00, "fee": 30.00}
                                                  ]
                                                }
                                                """
                                                        .formatted(okRef, todayDhaka, ord1Number)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.differenceKind").value("SHORT"))
                        .andExpect(jsonPath("$.difference").value(-20.00))
                        .andExpect(jsonPath("$.feesTotal").value(30.00))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID stlId = UUID.fromString(json.readTree(stlJson).get("id").asString());

        // Duplicate (courier, reference) -> 409 DUPLICATE_REFERENCE
        mockMvc.perform(
                        post("/api/v1/cod/settlements")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "courier": "STEADFAST",
                                          "reference": "SF-%s",
                                          "receivedOn": "%s",
                                          "receiveMethod": "BKASH",
                                          "amountReceived": 610.00,
                                          "note": "Over payment from bonus",
                                          "lines": [
                                            {"orderId": "%s", "codCollectedAmount": 600.00, "fee": 15.00}
                                          ]
                                        }
                                        """
                                                .formatted(
                                                        shortId().toUpperCase(), todayDhaka, ord2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.differenceKind").value("OVER"))
                .andExpect(jsonPath("$.difference").value(25.00));

        mockMvc.perform(
                        post("/api/v1/cod/settlements")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "courier": "PATHAO",
                                          "reference": "%s",
                                          "receivedOn": "%s",
                                          "receiveMethod": "BANK",
                                          "amountReceived": 10.00,
                                          "lines": [
                                            {"orderId": "%s", "codCollectedAmount": 10.00, "fee": 0.00}
                                          ]
                                        }
                                        """
                                                .formatted(okRef, todayDhaka, ord2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.DUPLICATE_REFERENCE));

        mockMvc.perform(get("/api/v1/cod/settlements").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + stlId + "')].settlementNumber").isNotEmpty());
        mockMvc.perform(get("/api/v1/cod/settlements/" + stlId).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1));
    }

    @Test
    @DisplayName(
            "concurrency: simultaneous COD collection exceeding expected and simultaneous settlements exceeding collected")
    void codCollectionAndSettlementConcurrencyRaces() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("CODRACE", "1000.00", "400.00");
        addOpeningStock(whId, item.variant().getId(), 20);
        Cookie ownerCookie = signIn("owner");

        UUID ord1 = createOrder(ownerCookie, item.variant().getSku(), "COD", null, true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Race 1: Two simultaneous 600.00 COD collections on 1000.00 expected -> 1 wins (201),
            // 1 fails (409)
            CountDownLatch ready1 = new CountDownLatch(2);
            CountDownLatch go1 = new CountDownLatch(1);
            Future<Integer> fc1 =
                    pool.submit(
                            () -> {
                                ready1.countDown();
                                go1.await();
                                return mockMvc.perform(
                                                post("/api/v1/orders/" + ord1 + "/cod-collections")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"courier\":\"PATHAO\",\"amount\":600.00}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> fc2 =
                    pool.submit(
                            () -> {
                                ready1.countDown();
                                go1.await();
                                return mockMvc.perform(
                                                post("/api/v1/orders/" + ord1 + "/cod-collections")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"courier\":\"PATHAO\",\"amount\":600.00}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            ready1.await();
            go1.countDown();
            List<Integer> cStatuses = List.of(fc1.get(), fc2.get());
            assertTrue(cStatuses.contains(201));
            assertTrue(cStatuses.contains(409));

            // Race 2: Two simultaneous settlements racing to settle 400.00 against the 600.00
            // collected -> 1 wins (201), 1 fails (409)
            CountDownLatch ready2 = new CountDownLatch(2);
            CountDownLatch go2 = new CountDownLatch(1);
            Future<Integer> fs1 =
                    pool.submit(
                            () -> {
                                ready2.countDown();
                                go2.await();
                                return mockMvc.perform(
                                                post("/api/v1/cod/settlements")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                """
                                                                {
                                                                  "courier": "PATHAO",
                                                                  "reference": "RCSTL-1-%s",
                                                                  "receiveMethod": "BANK",
                                                                  "amountReceived": 400.00,
                                                                  "lines": [{"orderId": "%s", "codCollectedAmount": 400.00, "fee": 0.00}]
                                                                }
                                                                """
                                                                        .formatted(
                                                                                shortId()
                                                                                        .toUpperCase(),
                                                                                ord1)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> fs2 =
                    pool.submit(
                            () -> {
                                ready2.countDown();
                                go2.await();
                                return mockMvc.perform(
                                                post("/api/v1/cod/settlements")
                                                        .cookie(ownerCookie)
                                                        .with(CsrfTestSupport.csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                """
                                                                {
                                                                  "courier": "PATHAO",
                                                                  "reference": "RCSTL-2-%s",
                                                                  "receiveMethod": "BANK",
                                                                  "amountReceived": 400.00,
                                                                  "lines": [{"orderId": "%s", "codCollectedAmount": 400.00, "fee": 0.00}]
                                                                }
                                                                """
                                                                        .formatted(
                                                                                shortId()
                                                                                        .toUpperCase(),
                                                                                ord1)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            ready2.await();
            go2.countDown();
            List<Integer> sStatuses = List.of(fs1.get(), fs2.get());
            assertTrue(sStatuses.contains(201));
            assertTrue(sStatuses.contains(409));

            // Total settled never exceeds collected (400 <= 600)
            JsonNode pos =
                    json.readTree(
                                    mockMvc.perform(
                                                    get("/api/v1/orders/" + ord1 + "/payments")
                                                            .cookie(ownerCookie))
                                            .andExpect(status().isOk())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("position");
            assertEquals(
                    0, new BigDecimal("600.00").compareTo(pos.get("codCollected").decimalValue()));
            assertEquals(
                    0,
                    new BigDecimal("400.00").compareTo(pos.get("codSettledGross").decimalValue()));
            assertEquals(
                    0,
                    new BigDecimal("200.00").compareTo(pos.get("codWithCourier").decimalValue()));
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID createOrder(
            Cookie actor, String sku, String mode, String advanceAmount, boolean confirmNow)
            throws Exception {
        String adv = advanceAmount == null ? "" : "\"advanceAmount\":\"" + advanceAmount + "\",";
        String body =
                """
                {
                  "customerName": "COD Customer",
                  "phone": "01712345678",
                  "address": "Mirpur 10",
                  "lines": [{"sku": "%s", "quantity": 1}],
                  %s
                  "paymentMode": "%s",
                  "confirmNow": %s
                }
                """
                        .formatted(sku, adv, mode, confirmNow);
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
}
