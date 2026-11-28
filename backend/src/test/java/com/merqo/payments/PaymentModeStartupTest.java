package com.merqo.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.merqo.MerqoApplication;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.pos.PosIntegrationSupport;

/**
 * Integration tests verifying {@code PAYMENT_MODE=manual} provider check behavior and {@code
 * PAYMENT_MODE=live} startup failure.
 */
@SpringBootTest(properties = "merqo.payment-mode=manual")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentModeStartupTest extends PosIntegrationSupport {

    @Autowired private ObjectMapper json;

    @Test
    @DisplayName(
            "manual mode exposes providerCheckEnabled=false and returns 409 VERIFIER_UNAVAILABLE on /check")
    void manualModeReturnsVerifierUnavailable() throws Exception {
        mockMvc.perform(get("/api/v1/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMode").value("manual"))
                .andExpect(jsonPath("$.providerCheckEnabled").value(false));

        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("MANM", "400.00", "200.00");
        addOpeningStock(whId, item.variant().getId(), 5);
        Cookie ownerCookie = signIn("owner");

        String ordBody =
                mockMvc.perform(
                                post("/api/v1/orders/manual")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "customerName": "Manual Mode Buyer",
                                                  "phone": "01712345678",
                                                  "address": "Banani",
                                                  "lines": [{"sku": "%s", "quantity": 1}],
                                                  "paymentMode": "FULL_PREPAID",
                                                  "confirmNow": false
                                                }
                                                """
                                                        .formatted(item.variant().getSku())))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID orderId = UUID.fromString(json.readTree(ordBody).get("id").asString());

        String payBody =
                mockMvc.perform(
                                post("/api/v1/orders/" + orderId + "/payments")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"method":"BKASH","amount":"400.00","reference":"MAN%s"}
                                                """
                                                        .formatted(shortId().toUpperCase())))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID paymentId = UUID.fromString(json.readTree(payBody).get("id").asString());

        mockMvc.perform(
                        post("/api/v1/payments/" + paymentId + "/check")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VERIFIER_UNAVAILABLE));
    }

    @Test
    @DisplayName("PAYMENT_MODE=live fails Spring startup cleanly with clear merchant API message")
    void liveModeFailsStartupCleanly() {
        Exception ex =
                assertThrows(
                        Exception.class,
                        () -> {
                            try (var ctx =
                                    new SpringApplicationBuilder(MerqoApplication.class)
                                            .web(WebApplicationType.SERVLET)
                                            .profiles("test")
                                            .run(
                                                    "--server.port=0",
                                                    "--merqo.payment-mode=live",
                                                    "--merqo.demo-mode=false",
                                                    "--spring.flyway.enabled=false")) {
                                assertEquals(true, ctx.isActive());
                            }
                        });
        StringBuilder fullMsg = new StringBuilder();
        Throwable cur = ex;
        while (cur != null) {
            if (cur.getMessage() != null) {
                fullMsg.append(cur.getMessage()).append(" | ");
            }
            cur = cur.getCause();
        }
        assertTrue(
                fullMsg.toString().contains("merchant API access"),
                "Expected clear merchant API failure message, got: " + fullMsg);
    }
}
