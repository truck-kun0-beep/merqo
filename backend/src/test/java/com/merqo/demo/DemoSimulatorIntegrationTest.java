package com.merqo.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.merqo.orders.MockWebsiteOrderSource;
import com.merqo.orders.Order;
import com.merqo.orders.OrderRepository;
import com.merqo.pos.PosIntegrationSupport;

/** Tests for {@link DemoSimulatorController} and {@link MockWebsiteOrderSource}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoSimulatorIntegrationTest extends PosIntegrationSupport {

    @Autowired private ObjectMapper json;
    @Autowired private MockWebsiteOrderSource mockSource;
    @Autowired private OrderRepository orderRepository;

    @Test
    @DisplayName(
            "owner can inspect simulator info and simulate real signed orders; non-owners get 403")
    void simulatorInfoAndSimulateOrder() throws Exception {
        assertEquals("mock", mockSource.mode());

        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("SIM", "420.00", "200.00");
        addOpeningStock(whId, item.variant().getId(), 10);

        Cookie ownerCookie = signIn("owner");
        Cookie ordersCookie = signIn("orders");

        // Non-owner rejected with 403
        mockMvc.perform(get("/api/v1/demo/simulator-info").cookie(ordersCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/api/v1/demo/simulate-order")
                                .cookie(ordersCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());

        // Owner sees demo client info & presets
        mockMvc.perform(get("/api/v1/demo/simulator-info").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoMode").value(true))
                .andExpect(
                        jsonPath("$.demoClient.keyId")
                                .value(DemoSimulatorController.DEMO_CLIENT_KEY_ID))
                .andExpect(jsonPath("$.sampleSkus").isArray());

        // Owner simulates a real signed order using item SKU
        String body =
                """
                {
                  "preset": "ADVANCE_COD_NAGAD",
                  "sku": "%s",
                  "quantity": 2
                }
                """
                        .formatted(item.variant().getSku());

        String resp =
                mockMvc.perform(
                                post("/api/v1/demo/simulate-order")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.accepted").value(true))
                        .andExpect(jsonPath("$.httpStatus").value(201))
                        .andExpect(jsonPath("$.order.status").value("PENDING_CONFIRMATION"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode root = json.readTree(resp);
        UUID orderId = UUID.fromString(root.get("order").get("orderId").asString());
        Order created = orderRepository.findById(orderId).orElseThrow();
        assertEquals("PENDING_CONFIRMATION", created.getStatus().name());
        assertTrue(root.get("signedHeaders").has("X-Merqo-Signature"));

        // Simulating UNKNOWN_SKU preset returns accepted=false with 422 UNKNOWN_SKU
        mockMvc.perform(
                        post("/api/v1/demo/simulate-order")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"preset\":\"UNKNOWN_SKU\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.httpStatus").value(422))
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_SKU"));
    }
}
