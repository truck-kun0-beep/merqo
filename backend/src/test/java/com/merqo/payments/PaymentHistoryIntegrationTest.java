package com.merqo.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.merqo.common.time.BusinessTime;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.pos.PosIntegrationSupport;

/**
 * Integration tests for Slice E: unified payment history &amp; summary, search by
 * phone/order/receipt/TrxID, filters, Dhaka-day ranges, pagination, and SQL union scope isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentHistoryIntegrationTest extends PosIntegrationSupport {

    @Autowired private ObjectMapper json;

    @Test
    @DisplayName(
            "history and summary surface all four sources, support search/filters/pagination, and prevent cross-scope union leaks")
    void unifiedHistorySearchFiltersSummaryAndScoping() throws Exception {
        UUID whId = locationId("WH");
        UUID dmId = locationId("DM");
        ProductFixture item = createProductFixture("HISTP", "500.00", "200.00");
        addOpeningStock(whId, item.variant().getId(), 20);
        addOpeningStock(dmId, item.variant().getId(), 20);

        Cookie ownerCookie = signIn("owner");
        Cookie managerDm = signIn("manager-dm");
        Cookie cashierDm = signIn("cashier");

        // User assigned at WH with PAYMENT_VIEW only (ONLINE_ORDER_STAFF lacks
        // COD_SETTLEMENT_MANAGE)
        Cookie whOrderStaff =
                createAndSignIn("hst.wh", AccountType.STAFF, "ONLINE_ORDER_STAFF", whId, null);

        // 1. POS_SALE at Dhanmondi (CASH 500.00)
        Cookie dmCashier = createAndSignIn("hst.pos", AccountType.STAFF, "MANAGER", dmId, null);
        openSession(dmCashier, dmId, "1000.00");
        String posBody =
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
                                                  "payment": {"method": "CASH", "tendered": "500.00"}
                                                }
                                                """
                                                        .formatted(dmId, item.variant().getId())))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String receiptNumber = json.readTree(posBody).get("receiptNumber").asString();

        // 2. Online FULL_PREPAID order at WH (500.00, verified BKASH + paid refund 500.00)
        String uniquePhone =
                "017" + String.format("%08d", Math.abs(shortId().hashCode()) % 100_000_000);
        String ordBody =
                mockMvc.perform(
                                post("/api/v1/orders/manual")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "customerName": "History Buyer",
                                                  "phone": "%s",
                                                  "address": "Uttara Sec 7",
                                                  "lines": [{"sku": "%s", "quantity": 1}],
                                                  "paymentMode": "FULL_PREPAID",
                                                  "confirmNow": false
                                                }
                                                """
                                                        .formatted(
                                                                uniquePhone,
                                                                item.variant().getSku())))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode ordNode = json.readTree(ordBody);
        UUID ordId = UUID.fromString(ordNode.get("id").asString());
        String ordNumber = ordNode.get("orderNumber").asString();
        String payTrx = "HSTTRX" + shortId().toUpperCase();
        String payBody =
                mockMvc.perform(
                                post("/api/v1/orders/" + ordId + "/payments")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"method":"BKASH","amount":"500.00","reference":"%s"}
                                                """
                                                        .formatted(payTrx)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID payId = UUID.fromString(json.readTree(payBody).get("id").asString());
        mockMvc.perform(
                        post("/api/v1/payments/" + payId + "/verify")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"decision\":\"VERIFIED\",\"confirmedAmount\":500.00,\"evidence\":\"Ok\"}"))
                .andExpect(status().isOk());

        // Refund payout (REFUND_PAYOUT = 200.00 CASH)
        String rfdBody =
                mockMvc.perform(
                                post("/api/v1/orders/" + ordId + "/refunds")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"amount\":200.00,\"reason\":\"CUSTOMER_REQUEST\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID rfdId = UUID.fromString(json.readTree(rfdBody).get("id").asString());
        mockMvc.perform(
                        post("/api/v1/refunds/" + rfdId + "/approve")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/refunds/" + rfdId + "/pay")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"payoutMethod\":\"CASH\",\"note\":\"Partial cash back\"}"))
                .andExpect(status().isOk());

        // 3. Confirmed COD order at WH + collection + COD_SETTLEMENT
        String codOrdBody =
                mockMvc.perform(
                                post("/api/v1/orders/manual")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "customerName": "COD Hist Buyer",
                                                  "phone": "%s",
                                                  "address": "Mohakhali",
                                                  "lines": [{"sku": "%s", "quantity": 1}],
                                                  "paymentMode": "COD",
                                                  "confirmNow": true
                                                }
                                                """
                                                        .formatted(
                                                                uniquePhone,
                                                                item.variant().getSku())))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID codOrdId = UUID.fromString(json.readTree(codOrdBody).get("id").asString());
        mockMvc.perform(
                        post("/api/v1/orders/" + codOrdId + "/cod-collections")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"courier\":\"PATHAO\",\"amount\":500.00}"))
                .andExpect(status().isCreated());
        String stlRef = "HSTSTL-" + shortId().toUpperCase();
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
                                          "receiveMethod": "BANK",
                                          "amountReceived": 480.00,
                                          "lines": [{"orderId": "%s", "codCollectedAmount": 500.00, "fee": 20.00}]
                                        }
                                        """
                                                .formatted(stlRef, codOrdId)))
                .andExpect(status().isCreated());

        // Owner queries all 4 sources cleanly
        LocalDate todayDhaka = BusinessTime.today(Clock.systemUTC());
        mockMvc.perform(
                        get("/api/v1/payments/history")
                                .param("q", receiptNumber)
                                .cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("POS_SALE"));

        mockMvc.perform(get("/api/v1/payments/history").param("q", payTrx).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("ORDER_PAYMENT"))
                .andExpect(jsonPath("$.items[0].status").value("VERIFIED"));

        // Phone search (formatted +880 ... matching normalized phone) returns order payment &
        // refund payout
        String formattedPhone =
                "+880 " + uniquePhone.substring(1, 5) + "-" + uniquePhone.substring(5);
        JsonNode phoneSearch =
                json.readTree(
                        mockMvc.perform(
                                        get("/api/v1/payments/history")
                                                .param("q", formattedPhone)
                                                .cookie(ownerCookie))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertEquals(2, phoneSearch.get("totalItems").asInt());

        // Order number search
        mockMvc.perform(get("/api/v1/payments/history").param("q", ordNumber).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));

        // Reference search matches settlement too
        mockMvc.perform(get("/api/v1/payments/history").param("q", stlRef).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("COD_SETTLEMENT"));

        // Dhaka-day date filter + Summary consistency across pages
        JsonNode p1 =
                json.readTree(
                        mockMvc.perform(
                                        get("/api/v1/payments/history")
                                                .param("fromDate", todayDhaka.toString())
                                                .param("toDate", todayDhaka.toString())
                                                .param("page", "0")
                                                .param("size", "2")
                                                .cookie(ownerCookie))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        int totalElements = p1.get("totalItems").asInt();
        BigDecimal rowSum = BigDecimal.ZERO;
        int rowCount = 0;
        int totalPages = p1.get("totalPages").asInt();
        for (int pg = 0; pg < totalPages; pg++) {
            JsonNode pgJson =
                    json.readTree(
                            mockMvc.perform(
                                            get("/api/v1/payments/history")
                                                    .param("fromDate", todayDhaka.toString())
                                                    .param("toDate", todayDhaka.toString())
                                                    .param("page", String.valueOf(pg))
                                                    .param("size", "2")
                                                    .cookie(ownerCookie))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            for (JsonNode row : pgJson.get("items")) {
                rowSum = rowSum.add(row.get("amount").decimalValue());
                rowCount++;
            }
        }
        JsonNode summaryJson =
                json.readTree(
                        mockMvc.perform(
                                        get("/api/v1/payments/summary")
                                                .param("fromDate", todayDhaka.toString())
                                                .param("toDate", todayDhaka.toString())
                                                .cookie(ownerCookie))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertEquals(totalElements, rowCount);
        assertEquals(rowCount, summaryJson.get("totalCount").asInt());
        assertEquals(0, rowSum.compareTo(summaryJson.get("totalAmount").decimalValue()));

        // Scoping: manager-dm sees DM POS row (receiptNumber), NEVER any ORDER_PAYMENT,
        // REFUND_PAYOUT, or COD_SETTLEMENT (Jackson whole-page check)
        JsonNode dmHist =
                json.readTree(
                        mockMvc.perform(
                                        get("/api/v1/payments/history")
                                                .param("size", "100")
                                                .cookie(managerDm))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        boolean sawPos = false;
        for (JsonNode row : dmHist.get("items")) {
            String rowType = row.get("type").asString();
            assertEquals(
                    "POS_SALE", rowType, "Branch manager history leaked non-POS type: " + rowType);
            if (receiptNumber.equals(row.get("documentNumber").asString())) {
                sawPos = true;
            }
        }
        assertTrue(sawPos);
        mockMvc.perform(
                        get("/api/v1/payments/history")
                                .param("locationId", whId.toString())
                                .cookie(managerDm))
                .andExpect(status().isNotFound());

        // Scoping: cashier-dm has no online or settlement rows in history
        JsonNode cashHist =
                json.readTree(
                        mockMvc.perform(get("/api/v1/payments/history").cookie(cashierDm))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertEquals(0, cashHist.get("items").size());

        // Scoping: WH user with PAYMENT_VIEW at WH but NOT COD_SETTLEMENT_MANAGE sees ORDER_PAYMENT
        // / REFUND_PAYOUT but zero COD_SETTLEMENT rows!
        JsonNode whHist =
                json.readTree(
                        mockMvc.perform(
                                        get("/api/v1/payments/history")
                                                .param("size", "100")
                                                .cookie(whOrderStaff))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        List<String> whTypes = new java.util.ArrayList<>();
        for (JsonNode row : whHist.get("items")) {
            String rowType = row.get("type").asString();
            assertFalse(
                    "COD_SETTLEMENT".equals(rowType),
                    "COD settlement leaked without COD_SETTLEMENT_MANAGE");
            whTypes.add(rowType);
        }
        assertTrue(whTypes.contains("ORDER_PAYMENT"));
        assertTrue(whTypes.contains("REFUND_PAYOUT"));
    }
}
