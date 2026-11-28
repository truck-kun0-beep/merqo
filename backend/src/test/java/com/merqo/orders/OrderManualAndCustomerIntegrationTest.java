package com.merqo.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditLogRepository;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.Scope;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.orders.domain.OrderStatus;
import com.merqo.pos.PosIntegrationSupport;
import com.merqo.promotion.PromotionService.ConflictAction;
import com.merqo.promotion.PromotionService.PromotionRequest;
import com.merqo.settings.SettingKey;

/**
 * Integration tests for manual phone/WhatsApp order creation (server-authoritative ONLINE catalog +
 * promotion pricing, immediate vs pending confirmation) and derived customer history / risk tiers
 * (`NEW`, `OK`, `WATCH`, `HIGH`) respecting {@code threshold.refusal_risk_count}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderManualAndCustomerIntegrationTest extends PosIntegrationSupport {

    @Autowired private ObjectMapper json;
    @Autowired private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("manual order uses server catalog + ONLINE promo price and ignores POS-only promo")
    void manualOrderPricingPromotionsAndConfirmNow() throws Exception {
        UUID whId = locationId("WH");
        UUID dmId = locationId("DM");
        ProductFixture onlinePromoItem = createProductFixture("MAN-ONL", "1000.00", "400.00");
        ProductFixture posPromoItem = createProductFixture("MAN-POS", "800.00", "300.00");
        addOpeningStock(whId, onlinePromoItem.variant().getId(), 10);
        addOpeningStock(whId, posPromoItem.variant().getId(), 10);

        // 10% ONLINE promotion on onlinePromoItem -> unitPrice becomes 900.00
        promotionService.create(
                ownerContext(),
                new PromotionRequest(
                        "Online 10% " + shortId(),
                        DiscountType.PERCENT,
                        new BigDecimal("10"),
                        Scope.ONLINE,
                        true,
                        Set.of(),
                        Set.of(onlinePromoItem.variant().getId()),
                        null,
                        null,
                        null,
                        ConflictAction.REFUSE));

        // 25% POS-only promotion at DM on posPromoItem -> must NOT apply on manual online order!
        promotionService.create(
                ownerContext(),
                new PromotionRequest(
                        "POS 25% " + shortId(),
                        DiscountType.PERCENT,
                        new BigDecimal("25"),
                        Scope.POS,
                        false,
                        Set.of(dmId),
                        Set.of(posPromoItem.variant().getId()),
                        null,
                        null,
                        null,
                        ConflictAction.REFUSE));

        Cookie whStaff = signIn("orders");

        // Create unconfirmed manual order
        String body =
                """
                {
                  "customerName": "Farhana Akter",
                  "phone": "01799887766",
                  "address": "House 9, Uttara",
                  "district": "Dhaka",
                  "lines": [
                    {"variantId": "%s", "quantity": 1},
                    {"sku": "%s", "quantity": 1}
                  ],
                  "shippingFee": 60.00,
                  "discountTotal": 0.00,
                  "paymentMode": "COD",
                  "confirmNow": false
                }
                """
                        .formatted(
                                onlinePromoItem.variant().getId(), posPromoItem.variant().getSku());

        String response =
                mockMvc.perform(
                                post("/api/v1/orders/manual")
                                        .cookie(whStaff)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.channel").value("MANUAL"))
                        .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                        .andExpect(jsonPath("$.subtotal").value(1700.00))
                        .andExpect(jsonPath("$.total").value(1760.00))
                        .andExpect(jsonPath("$.lines[0].unitPrice").value(900.00))
                        .andExpect(jsonPath("$.lines[1].unitPrice").value(800.00))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID orderId = UUID.fromString(json.readTree(response).get("id").asString());
        assertEquals(
                0,
                inventoryService.balanceOf(whId, onlinePromoItem.variant().getId()).getReserved());

        var audit =
                auditLogRepository
                        .findByEntityTypeAndEntityIdOrderByOccurredAtAsc(
                                "order", orderId.toString())
                        .stream()
                        .filter(a -> AuditAction.MANUAL_ORDER_CREATED.name().equals(a.getAction()))
                        .findFirst()
                        .orElse(null);
        assertNotNull(audit);

        // Create immediate-confirm manual order -> reserves warehouse stock atomically
        String instantBody =
                """
                {
                  "customerName": "Farhana Akter",
                  "phone": "+880 1799-887766",
                  "address": "House 9, Uttara",
                  "lines": [{"variantId": "%s", "quantity": 2}],
                  "shippingFee": 0.00,
                  "discountTotal": 0.00,
                  "paymentMode": "FULL_PREPAID",
                  "claimedAdvanceMethod": "BKASH",
                  "claimedAdvanceReference": "MAN-TRX-1",
                  "confirmNow": true
                }
                """
                        .formatted(onlinePromoItem.variant().getId());

        mockMvc.perform(
                        post("/api/v1/orders/manual")
                                .cookie(whStaff)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(instantBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.total").value(1800.00))
                .andExpect(jsonPath("$.advanceAmount").value(1800.00))
                .andExpect(jsonPath("$.codAmount").value(0.00));

        assertEquals(
                2,
                inventoryService.balanceOf(whId, onlinePromoItem.variant().getId()).getReserved());
    }

    @Test
    @DisplayName(
            "customer history classifies NEW, OK, WATCH, HIGH and honors threshold.refusal_risk_count")
    void customerHistoryAndRiskBadgeProgression() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("RISK", "500.00", "200.00");
        addOpeningStock(whId, item.variant().getId(), 20);

        Cookie ownerCookie = signIn("owner");
        Cookie whStaff = signIn("orders");

        String phone =
                "0177" + String.format("%07d", Math.abs(UUID.randomUUID().hashCode()) % 10_000_000);

        // 1. Zero orders -> NEW
        mockMvc.perform(get("/api/v1/customers/history").param("phone", phone).cookie(whStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskTier").value("NEW"))
                .andExpect(jsonPath("$.history.total").value(0));

        // 2. One DELIVERED order -> OK
        UUID deliveredId = createConfirmedManualOrder(whStaff, phone, item.variant().getId());
        jdbcTemplate.update(
                "UPDATE orders SET status = ? WHERE id = ?",
                OrderStatus.DELIVERED.name(),
                deliveredId);

        mockMvc.perform(get("/api/v1/customers/history").param("phone", phone).cookie(whStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskTier").value("OK"))
                .andExpect(jsonPath("$.history.delivered").value(1))
                .andExpect(jsonPath("$.history.total").value(1));

        // 3. Two cancelled/declined orders -> WATCH
        UUID decId = createConfirmedManualOrder(whStaff, phone, item.variant().getId());
        jdbcTemplate.update(
                "UPDATE orders SET status = ? WHERE id = ?", OrderStatus.DECLINED.name(), decId);
        UUID canId = createConfirmedManualOrder(whStaff, phone, item.variant().getId());
        jdbcTemplate.update(
                "UPDATE orders SET status = ? WHERE id = ?", OrderStatus.CANCELLED.name(), canId);

        mockMvc.perform(get("/api/v1/customers/history").param("phone", phone).cookie(whStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskTier").value("WATCH"))
                .andExpect(jsonPath("$.history.declined").value(1))
                .andExpect(jsonPath("$.history.cancelled").value(1));

        // 4. One REFUSED order stays WATCH at default threshold 2; updating setting threshold to 1
        // turns
        // it into HIGH (proving threshold setting drives HIGH & HIGH beats WATCH!)
        UUID refId = createConfirmedManualOrder(whStaff, phone, item.variant().getId());
        jdbcTemplate.update(
                "UPDATE orders SET status = ? WHERE id = ?", OrderStatus.REFUSED.name(), refId);

        mockMvc.perform(get("/api/v1/customers/history").param("phone", phone).cookie(whStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskTier").value("WATCH"));

        try {
            mockMvc.perform(
                            put("/api/v1/settings")
                                    .cookie(ownerCookie)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"values\":{\""
                                                    + SettingKey.THRESHOLD_REFUSAL_RISK_COUNT
                                                            .getKey()
                                                    + "\":\"1\"}}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/customers/history").param("phone", phone).cookie(whStaff))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.riskTier").value("HIGH"))
                    .andExpect(jsonPath("$.history.refused").value(1));
        } finally {
            mockMvc.perform(
                            put("/api/v1/settings")
                                    .cookie(ownerCookie)
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"values\":{\""
                                                    + SettingKey.THRESHOLD_REFUSAL_RISK_COUNT
                                                            .getKey()
                                                    + "\":\"2\"}}"))
                    .andExpect(status().isOk());
        }
    }

    private UUID createConfirmedManualOrder(Cookie staffCookie, String phone, UUID variantId)
            throws Exception {
        String body =
                """
                {
                  "customerName": "Risk Probe Buyer",
                  "phone": "%s",
                  "address": "Mirpur 2, Dhaka",
                  "lines": [{"variantId": "%s", "quantity": 1}],
                  "paymentMode": "COD",
                  "confirmNow": false
                }
                """
                        .formatted(phone, variantId);
        String resp =
                mockMvc.perform(
                                post("/api/v1/orders/manual")
                                        .cookie(staffCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode root = json.readTree(resp);
        return UUID.fromString(root.get("id").asString());
    }
}
