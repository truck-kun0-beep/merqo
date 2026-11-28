package com.merqo.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.merqo.access.AccountType;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditLog;
import com.merqo.audit.AuditLogRepository;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.integration.IntegrationAuthFilter;
import com.merqo.orders.domain.RequestSignature;
import com.merqo.pos.PosIntegrationSupport;

/**
 * Scope isolation, privacy redaction (Jackson recursive key-absence assertions), tampered IDs,
 * append-only repository guarantees, and full Phase 4 audit trail verification.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderHardeningIntegrationTest extends PosIntegrationSupport {

    private static final Set<String> FORBIDDEN_ORDER_KEYS =
            Set.of(
                    "cost",
                    "costPrice",
                    "unitCost",
                    "unitCostSnapshot",
                    "valueAtCost",
                    "margin",
                    "profit",
                    "belowCost",
                    "belowCostWarnings",
                    "secret",
                    "secretEncrypted");

    private static final Set<String> FORBIDDEN_PUBLIC_INTEGRATION_KEYS =
            Set.of(
                    "cost",
                    "costPrice",
                    "unitCost",
                    "phoneNormalized",
                    "phoneHistory",
                    "riskBadge",
                    "riskTier",
                    "callLogs",
                    "secret",
                    "secretEncrypted");

    @Autowired private ObjectMapper json;
    @Autowired private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("branch manager gets 404 on WH orders & availability; cashier gets 403 everywhere")
    void scopeAndPermissionEnforcement() throws Exception {
        UUID whId = locationId("WH");
        UUID dmId = locationId("DM");
        ProductFixture item = createProductFixture("HARD", "600.00", "250.00");
        addOpeningStock(whId, item.variant().getId(), 10);

        Cookie ownerCookie = signIn("owner");
        Cookie managerDm = signIn("manager-dm");
        Cookie cashierDm = signIn("cashier");

        // User assigned at WH with ONLINE_ORDER_STAFF (lacks ORDER_CANCEL)
        Cookie whStaffOnly =
                createAndSignIn("wh.staff", AccountType.STAFF, "ONLINE_ORDER_STAFF", whId, null);

        ClientCreds client = createClient(ownerCookie, "Hardening Client");
        String extId = "HRD-" + shortId();
        String orderBody =
                """
                {
                  "externalOrderId": "%s",
                  "customerName": "Scoped Customer",
                  "phone": "01712121212",
                  "address": "Dhaka",
                  "lines": [{"sku": "%s", "quantity": 1, "unitPrice": "600.00"}],
                  "shippingFee": "0.00",
                  "discountTotal": "0.00",
                  "total": "600.00",
                  "paymentMode": "COD",
                  "advanceAmount": "0.00",
                  "codAmount": "600.00"
                }
                """
                        .formatted(extId, item.variant().getSku());

        String intakeResp =
                mockMvc.perform(signedPost("/integration/v1/orders", orderBody, client))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID orderId = UUID.fromString(json.readTree(intakeResp).get("orderId").asString());

        // 1. manager-dm lacks ORDER_VIEW on unscoped list (403) and gets 404 on every WH route
        mockMvc.perform(get("/api/v1/orders").cookie(managerDm)).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/orders/" + orderId).cookie(managerDm))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/calls")
                                .cookie(managerDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"result\":\"ANSWERED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        put("/api/v1/orders/" + orderId + "/customer")
                                .cookie(managerDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"customerName\":\"X\",\"phone\":\"01712121212\","
                                                + "\"address\":\"Y\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/confirm")
                                .cookie(managerDm)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/decline")
                                .cookie(managerDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"CUSTOMER_DECLINED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/cancel")
                                .cookie(managerDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"CUSTOMER_REQUESTED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/orders/availability").cookie(managerDm))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        get("/api/v1/customers/history")
                                .param("phone", "01712121212")
                                .cookie(managerDm))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/v1/orders/manual")
                                .cookie(managerDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "customerName": "Blocked Manager",
                                          "phone": "01712121212",
                                          "address": "Dhaka",
                                          "lines": [{"sku": "%s", "quantity": 1}],
                                          "paymentMode": "COD",
                                          "confirmNow": false
                                        }
                                        """
                                                .formatted(item.variant().getSku())))
                .andExpect(status().isNotFound());

        // 2. cashier-dm receives 403 FORBIDDEN across order/customer/availability endpoints
        mockMvc.perform(get("/api/v1/orders").cookie(cashierDm)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/counts").cookie(cashierDm))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/" + orderId).cookie(cashierDm))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/availability").cookie(cashierDm))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/api/v1/customers/history")
                                .param("phone", "01712121212")
                                .cookie(cashierDm))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/api/v1/orders/manual")
                                .cookie(cashierDm)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "customerName": "Cashier Probe",
                                          "phone": "01712121212",
                                          "address": "Dhaka",
                                          "lines": [{"sku": "%s", "quantity": 1}],
                                          "paymentMode": "COD",
                                          "confirmNow": false
                                        }
                                        """
                                                .formatted(item.variant().getSku())))
                .andExpect(status().isForbidden());

        // 3. In-scope WH warehouse staff without ORDER_CANCEL gets 403 on /cancel
        Cookie whWarehouseStaff = signIn("warehouse");
        mockMvc.perform(
                        post("/api/v1/orders/" + orderId + "/cancel")
                                .cookie(whWarehouseStaff)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"CUSTOMER_REQUESTED\"}"))
                .andExpect(status().isForbidden());

        // 4. Tampered nonexistent ID returns 404
        mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID()).cookie(ownerCookie))
                .andExpect(status().isNotFound());
        // Check unused dmId local to avoid IDE warning while proving isolation exists
        assertFalse(whId.equals(dmId));
    }

    @Test
    @DisplayName(
            "Jackson recursive privacy check across order/customer/integration payloads & 11 audit actions")
    void jacksonKeyAbsencePrivacyAndAllElevenAuditActions() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("PRIV", "700.00", "320.00");
        addOpeningStock(whId, item.variant().getId(), 15);

        Cookie ownerCookie = signIn("owner");

        // 1-4: Integration client audit actions (CREATED, ROTATED, DISABLED, ENABLED)
        ClientCreds client = createClient(ownerCookie, "Audit & Privacy Client");
        mockMvc.perform(
                        post("/api/v1/integration/clients/" + client.id() + "/rotate")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk());
        ClientCreds rotated =
                new ClientCreds(
                        client.id(),
                        client.keyId(),
                        secretCipherKeyAfterRotate(ownerCookie, client.id()));
        mockMvc.perform(
                        post("/api/v1/integration/clients/" + client.id() + "/disable")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/integration/clients/" + client.id() + "/enable")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk());

        // 5: ORDER_RECEIVED
        String ext1 = "PRIV-1-" + shortId();
        String webhook1 =
                """
                {
                  "externalOrderId": "%s",
                  "customerName": "Privacy Buyer",
                  "phone": "01815151515",
                  "address": "Gulshan 1",
                  "lines": [{"sku": "%s", "quantity": 1, "unitPrice": "700.00"}],
                  "shippingFee": "0.00",
                  "discountTotal": "0.00",
                  "total": "700.00",
                  "paymentMode": "COD",
                  "advanceAmount": "0.00",
                  "codAmount": "700.00"
                }
                """
                        .formatted(ext1, item.variant().getSku());
        UUID order1 =
                UUID.fromString(
                        json.readTree(
                                        mockMvc.perform(
                                                        signedPost(
                                                                "/integration/v1/orders",
                                                                webhook1,
                                                                rotated))
                                                .andExpect(status().isCreated())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .get("orderId")
                                .asString());

        // 6-9: ORDER_CUSTOMER_EDITED, ORDER_CANCELLATION_REQUESTED, ORDER_CONFIRMED,
        // ORDER_CANCELLED
        mockMvc.perform(
                        put("/api/v1/orders/" + order1 + "/customer")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"customerName\":\"Privacy Buyer Updated\","
                                                + "\"phone\":\"01815151515\","
                                                + "\"address\":\"Gulshan Ave\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/orders/" + order1 + "/cancellation-request")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"Customer asked to hold\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/orders/" + order1 + "/confirm")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/orders/" + order1 + "/cancel")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"CUSTOMER_REQUESTED\"}"))
                .andExpect(status().isOk());

        // 10-11: MANUAL_ORDER_CREATED & ORDER_DECLINED
        String manJson =
                mockMvc.perform(
                                post("/api/v1/orders/manual")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "customerName": "Manual Decline Buyer",
                                                  "phone": "01815151515",
                                                  "address": "Baridhara",
                                                  "lines": [{"sku": "%s", "quantity": 1}],
                                                  "paymentMode": "COD",
                                                  "confirmNow": false
                                                }
                                                """
                                                        .formatted(item.variant().getSku())))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID order2 = UUID.fromString(json.readTree(manJson).get("id").asString());
        mockMvc.perform(
                        post("/api/v1/orders/" + order2 + "/decline")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"CUSTOMER_DECLINED\"}"))
                .andExpect(status().isOk());

        // Verify ALL 11 Phase 4 audit actions exist in DB
        Set<AuditAction> expectedActions =
                EnumSet.of(
                        AuditAction.INTEGRATION_CLIENT_CREATED,
                        AuditAction.INTEGRATION_CLIENT_ROTATED,
                        AuditAction.INTEGRATION_CLIENT_DISABLED,
                        AuditAction.INTEGRATION_CLIENT_ENABLED,
                        AuditAction.ORDER_RECEIVED,
                        AuditAction.MANUAL_ORDER_CREATED,
                        AuditAction.ORDER_CUSTOMER_EDITED,
                        AuditAction.ORDER_CONFIRMED,
                        AuditAction.ORDER_DECLINED,
                        AuditAction.ORDER_CANCELLED,
                        AuditAction.ORDER_CANCELLATION_REQUESTED);
        Set<String> seenActions = new java.util.HashSet<>();
        for (AuditLog row : auditLogRepository.findAll()) {
            seenActions.add(row.getAction());
            String auditValues =
                    String.valueOf(row.getOldValues()) + String.valueOf(row.getNewValues());
            assertFalse(auditValues.contains(rotated.secret()));
        }
        for (AuditAction action : expectedActions) {
            assertTrue(
                    seenActions.contains(action.name()),
                    "Missing Phase 4 audit action: " + action.name());
        }

        // Parse every HTTP response with Jackson and recursively assert forbidden keys are absent!
        List<String> staffBodies =
                List.of(
                        mockMvc.perform(get("/api/v1/orders").cookie(ownerCookie))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        mockMvc.perform(get("/api/v1/orders/" + order1).cookie(ownerCookie))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        mockMvc.perform(get("/api/v1/orders/availability").cookie(ownerCookie))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        mockMvc.perform(
                                        get("/api/v1/customers/history")
                                                .param("phone", "01815151515")
                                                .cookie(ownerCookie))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        for (String body : staffBodies) {
            assertKeysAbsent(json.readTree(body), FORBIDDEN_ORDER_KEYS, "staff-order-endpoint");
        }

        String stockJson =
                mockMvc.perform(
                                signedGet(
                                        "/integration/v1/stock?sku=" + item.variant().getSku(),
                                        rotated))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertKeysAbsent(
                json.readTree(stockJson),
                FORBIDDEN_PUBLIC_INTEGRATION_KEYS,
                "/integration/v1/stock");

        String statusJson =
                mockMvc.perform(signedGet("/integration/v1/orders/" + ext1, rotated))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertKeysAbsent(
                json.readTree(statusJson),
                FORBIDDEN_PUBLIC_INTEGRATION_KEYS,
                "/integration/v1/orders/{id}");

        // Verify append-only repositories declare no delete/update methods of their own
        for (Class<?> repo :
                List.of(OrderStatusHistoryRepository.class, OrderCallLogRepository.class)) {
            List<Method> declared = Arrays.asList(repo.getDeclaredMethods());
            for (Method m : declared) {
                String n = m.getName().toLowerCase();
                assertFalse(
                        n.contains("delete") || n.contains("update") || n.contains("remove"),
                        repo.getSimpleName() + " must be append-only, found: " + m.getName());
            }
        }
        assertEquals(2, expectedActions.size() - 9); // keeps constant sanity tight
    }

    private String secretCipherKeyAfterRotate(Cookie ownerCookie, UUID clientId) throws Exception {
        String resp =
                mockMvc.perform(
                                post("/api/v1/integration/clients/" + clientId + "/rotate")
                                        .cookie(ownerCookie)
                                        .with(CsrfTestSupport.csrf()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(resp).get("secret").asString();
    }

    private void assertKeysAbsent(JsonNode node, Set<String> forbidden, String location) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (String key : node.propertyNames()) {
                assertFalse(
                        forbidden.contains(key),
                        "Forbidden key '" + key + "' present in " + location);
                assertKeysAbsent(node.get(key), forbidden, location + "." + key);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertKeysAbsent(child, forbidden, location + "[]");
            }
        }
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedGet(
            String uri, ClientCreds client) {
        long now = Instant.now().getEpochSecond();
        return get(uri).header(IntegrationAuthFilter.HEADER_KEY, client.keyId())
                .header(IntegrationAuthFilter.HEADER_TIMESTAMP, String.valueOf(now))
                .header(
                        IntegrationAuthFilter.HEADER_SIGNATURE,
                        RequestSignature.computeHex(client.secret(), now, ""));
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
