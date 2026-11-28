package com.merqo.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import com.merqo.common.error.ErrorCodes;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.integration.IntegrationAuthFilter;
import com.merqo.integration.IntegrationClientRepository;
import com.merqo.integration.IntegrationInbox;
import com.merqo.integration.IntegrationInboxRepository;
import com.merqo.inventory.InventoryPolicy;
import com.merqo.inventory.InventoryPolicyRepository;
import com.merqo.location.Location;
import com.merqo.orders.domain.RequestSignature;
import com.merqo.pos.PosIntegrationSupport;

/**
 * Integration tests for the external website order intake pipeline, inbox idempotency, concurrency,
 * soft holds, and online stock availability formula.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderIntakeIntegrationTest extends PosIntegrationSupport {

    @Autowired private ObjectMapper json;
    @Autowired private IntegrationClientRepository clientRepository;
    @Autowired private IntegrationInboxRepository inboxRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderLineRepository orderLineRepository;
    @Autowired private InventoryPolicyRepository policyRepository;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> createdClientIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID clientId : createdClientIds) {
            jdbc.update(
                    "DELETE FROM integration_inbox WHERE client_id = ? AND order_id IS NULL",
                    clientId);
        }
    }

    @Test
    @DisplayName(
            "valid order creates normalized customer, snapshots, history, and updates stock soft holds")
    void validOrderAndStockAvailabilityFormula() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture shirt = createProductFixture("INTAKE", "500.00", "250.00");
        addOpeningStock(whId, shirt.variant().getId(), 15);
        policyRepository.save(
                new InventoryPolicy(
                        UUID.randomUUID(), whId, shirt.variant().getId(), 2, 20, 3, Instant.now()));

        Cookie ownerCookie = signInOwner();
        ClientCreds client = createClient(ownerCookie, "Valid Intake Client");

        // Before order: onHand 15 - reserved 0 - softHolds 0 - safety 3 = 12 available
        mockMvc.perform(signedGet("/integration/v1/stock?sku=" + shirt.variant().getSku(), client))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].available").value(12))
                .andExpect(jsonPath("$.items[0].inStock").value(true));

        String extId = "WEB-" + shortId();
        String payload =
                orderPayload(
                        extId,
                        "Rafiq Ahmed",
                        "+880 1711-223344",
                        "House 12, Road 4, Dhanmondi",
                        shirt.variant().getSku(),
                        2,
                        "500.00",
                        "60.00",
                        "0.00",
                        "1060.00",
                        "ADVANCE_COD",
                        "200.00",
                        "860.00",
                        "BKASH",
                        "TXN-RAW-999");

        mockMvc.perform(signedPost("/integration/v1/orders", payload, client))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.orderNumber").value(org.hamcrest.Matchers.startsWith("ORD-")))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.priceMismatch").value(false))
                .andExpect(jsonPath("$.invalidPhone").value(false))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        Order saved =
                orderRepository
                        .findByIntegrationClientIdAndExternalOrderId(client.id(), extId)
                        .orElseThrow();
        assertEquals("01711223344", saved.getPhoneNormalized());
        assertNotNull(saved.getCustomerId());
        assertEquals(0, new BigDecimal("1060.00").compareTo(saved.getTotal()));
        List<OrderLine> lines = orderLineRepository.findByOrderIdOrderByLineIndexAsc(saved.getId());
        assertEquals(1, lines.size());
        assertEquals(shirt.variant().getSku(), lines.getFirst().getSkuSnapshot());
        assertEquals(
                0, new BigDecimal("500.00").compareTo(lines.getFirst().getCatalogOnlinePrice()));

        // Soft holds now 2 -> available drops from 12 to 10
        mockMvc.perform(signedGet("/integration/v1/stock?sku=" + shirt.variant().getSku(), client))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].available").value(10));

        // Status endpoint scoped to client works and never exposes internal fields
        MvcResult statusResult =
                mockMvc.perform(signedGet("/integration/v1/orders/" + extId, client))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.orderNumber").value(saved.getOrderNumber()))
                        .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                        .andReturn();
        JsonNode statusJson = json.readTree(statusResult.getResponse().getContentAsString());
        assertFalse(statusJson.has("phoneNormalized"));
        assertFalse(statusJson.has("riskBadge"));
        assertFalse(statusJson.has("cost"));

        // Different client cannot read another client's external order -> 404
        ClientCreds otherClient = createClient(ownerCookie, "Other Store Client");
        mockMvc.perform(signedGet("/integration/v1/orders/" + extId, otherClient))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("idempotent replay returns 200, changed payload returns 409 IDEMPOTENCY_CONFLICT")
    void idempotencyAndConflictRules() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("IDEMP", "300.00", "150.00");
        addOpeningStock(whId, item.variant().getId(), 10);

        Cookie ownerCookie = signInOwner();
        ClientCreds client = createClient(ownerCookie, "Idempotency Client");
        String extId = "WEB-" + shortId();
        String payload =
                orderPayload(
                        extId,
                        "Nadia Karim",
                        "01811223344",
                        "Mirpur 10, Dhaka",
                        item.variant().getSku(),
                        1,
                        "300.00",
                        "50.00",
                        "0.00",
                        "350.00",
                        "COD",
                        "0.00",
                        "350.00",
                        null,
                        null);

        mockMvc.perform(signedPost("/integration/v1/orders", payload, client))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        mockMvc.perform(signedPost("/integration/v1/orders", payload, client))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        String alteredPayload =
                orderPayload(
                        extId,
                        "Nadia Karim",
                        "01811223344",
                        "Mirpur 10, Dhaka",
                        item.variant().getSku(),
                        2,
                        "300.00",
                        "50.00",
                        "0.00",
                        "650.00",
                        "COD",
                        "0.00",
                        "650.00",
                        null,
                        null);

        mockMvc.perform(signedPost("/integration/v1/orders", alteredPayload, client))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName(
            "concurrent identical webhook deliveries produce exactly one order and one PROCESSED inbox row")
    void concurrentDuplicateDeliveriesCreateOnlyOneOrder() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("CONC", "400.00", "200.00");
        addOpeningStock(whId, item.variant().getId(), 10);

        Cookie ownerCookie = signInOwner();
        ClientCreds client = createClient(ownerCookie, "Concurrency Client");
        String extId = "CONC-" + shortId();
        String payload =
                orderPayload(
                        extId,
                        "Tariq Hasan",
                        "01911223344",
                        "Uttara Sector 7, Dhaka",
                        item.variant().getSku(),
                        1,
                        "400.00",
                        "0.00",
                        "0.00",
                        "400.00",
                        "COD",
                        "0.00",
                        "400.00",
                        null,
                        null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Integer> task =
                    () -> {
                        ready.countDown();
                        go.await();
                        return mockMvc.perform(
                                        signedPost("/integration/v1/orders", payload, client))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            Future<Integer> f1 = pool.submit(task);
            Future<Integer> f2 = pool.submit(task);
            ready.await();
            go.countDown();

            List<Integer> statuses = List.of(f1.get(), f2.get());
            assertTrue(statuses.contains(201), "One call must return 201: " + statuses);
            assertTrue(statuses.contains(200), "Second call must replay with 200: " + statuses);
        } finally {
            pool.shutdownNow();
        }

        IntegrationInbox inbox =
                inboxRepository.findByClientIdAndExternalOrderId(client.id(), extId).orElseThrow();
        assertEquals(IntegrationInbox.Status.PROCESSED, inbox.getStatus());
        assertEquals(1, inbox.getAttempts());
        Integer orderCount =
                jdbc.queryForObject(
                        "SELECT count(*) FROM orders WHERE integration_client_id = ? AND"
                                + " external_order_id = ?",
                        Integer.class,
                        client.id(),
                        extId);
        assertEquals(1, orderCount);
    }

    @Test
    @DisplayName(
            "unknown SKU marks inbox FAILED with attempt count, retry succeeds; price mismatch & bad phone handled cleanly")
    void validationEdgeCasesAndFailedInboxRetry() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture validItem = createProductFixture("EDGE", "550.00", "250.00");
        addOpeningStock(whId, validItem.variant().getId(), 5);

        Cookie ownerCookie = signInOwner();
        ClientCreds client = createClient(ownerCookie, "Edge Case Client");

        // 1. Unknown SKU -> 422 UNKNOWN_SKU, inbox marked FAILED, then retry with valid SKU -> 201
        String retryExtId = "RETRY-" + shortId();
        String badSkuPayload =
                orderPayload(
                        retryExtId,
                        "Salma Begum",
                        "01611223344",
                        "Banani, Dhaka",
                        "DOES-NOT-EXIST-SKU",
                        1,
                        "550.00",
                        "0.00",
                        "0.00",
                        "550.00",
                        "COD",
                        "0.00",
                        "550.00",
                        null,
                        null);
        mockMvc.perform(signedPost("/integration/v1/orders", badSkuPayload, client))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNKNOWN_SKU));

        IntegrationInbox failedInbox =
                inboxRepository
                        .findByClientIdAndExternalOrderId(client.id(), retryExtId)
                        .orElseThrow();
        assertEquals(IntegrationInbox.Status.FAILED, failedInbox.getStatus());
        assertEquals(ErrorCodes.UNKNOWN_SKU, failedInbox.getErrorCode());
        assertNull(failedInbox.getOrderId());
        assertEquals(1, failedInbox.getAttempts());

        String fixedPayload =
                orderPayload(
                        retryExtId,
                        "Salma Begum",
                        "01611223344",
                        "Banani, Dhaka",
                        validItem.variant().getSku(),
                        1,
                        "550.00",
                        "0.00",
                        "0.00",
                        "550.00",
                        "COD",
                        "0.00",
                        "550.00",
                        null,
                        null);
        mockMvc.perform(signedPost("/integration/v1/orders", fixedPayload, client))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"));

        IntegrationInbox processedInbox =
                inboxRepository
                        .findByClientIdAndExternalOrderId(client.id(), retryExtId)
                        .orElseThrow();
        assertEquals(IntegrationInbox.Status.PROCESSED, processedInbox.getStatus());
        assertEquals(2, processedInbox.getAttempts());

        // 2. Price mismatch (490 vs 550) & unnormalizable phone accepted and flagged
        String flaggedExtId = "FLAG-" + shortId();
        String flaggedPayload =
                orderPayload(
                        flaggedExtId,
                        "Kamrul Islam",
                        "not-a-bd-phone",
                        "Sylhet Town",
                        validItem.variant().getSku(),
                        1,
                        "490.00",
                        "0.00",
                        "0.00",
                        "490.00",
                        "FULL_PREPAID",
                        "490.00",
                        "0.00",
                        "NAGAD",
                        "NG-555");
        mockMvc.perform(signedPost("/integration/v1/orders", flaggedPayload, client))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceMismatch").value(true))
                .andExpect(jsonPath("$.invalidPhone").value(true));

        Order flaggedOrder =
                orderRepository
                        .findByIntegrationClientIdAndExternalOrderId(client.id(), flaggedExtId)
                        .orElseThrow();
        assertTrue(flaggedOrder.isPriceMismatch());
        assertNull(flaggedOrder.getPhoneNormalized());
        assertNull(flaggedOrder.getCustomerId());

        // 3. Total arithmetic mismatch -> 422 ORDER_TOTAL_MISMATCH
        String mismatchPayload =
                orderPayload(
                        "TM-" + shortId(),
                        "Test Buyer",
                        "01711000111",
                        "Dhaka",
                        validItem.variant().getSku(),
                        1,
                        "550.00",
                        "0.00",
                        "0.00",
                        "999.00",
                        "COD",
                        "0.00",
                        "999.00",
                        null,
                        null);
        mockMvc.perform(signedPost("/integration/v1/orders", mismatchPayload, client))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.ORDER_TOTAL_MISMATCH));
    }

    @Test
    @DisplayName(
            "missing active online fulfillment warehouse rejects orders with 409 ONLINE_LOCATION_NOT_SET")
    void missingOnlineWarehouseReturns409AndRestoresCleanly() throws Exception {
        UUID whId = locationId("WH");
        ProductFixture item = createProductFixture("NOWH", "200.00", "100.00");
        Cookie ownerCookie = signInOwner();
        ClientCreds client = createClient(ownerCookie, "No Warehouse Client");

        Location wh = locationRepository.findById(whId).orElseThrow();
        wh.setOnlineFulfillment(false);
        locationRepository.saveAndFlush(wh);
        try {
            String payload =
                    orderPayload(
                            "NOWH-" + shortId(),
                            "Test Buyer",
                            "01711000222",
                            "Dhaka",
                            item.variant().getSku(),
                            1,
                            "200.00",
                            "0.00",
                            "0.00",
                            "200.00",
                            "COD",
                            "0.00",
                            "200.00",
                            null,
                            null);
            mockMvc.perform(signedPost("/integration/v1/orders", payload, client))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value(ErrorCodes.ONLINE_LOCATION_NOT_SET));
        } finally {
            wh.setOnlineFulfillment(true);
            locationRepository.saveAndFlush(wh);
        }
    }

    private Cookie signInOwner() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"owner\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        return result.getResponse().getCookie(SESSION_COOKIE);
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
        UUID id = UUID.fromString(root.get("client").get("id").asString());
        createdClientIds.add(id);
        return new ClientCreds(
                id, root.get("client").get("keyId").asString(), root.get("secret").asString());
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

    private static String orderPayload(
            String externalOrderId,
            String customerName,
            String phone,
            String address,
            String sku,
            int quantity,
            String unitPrice,
            String shippingFee,
            String discountTotal,
            String total,
            String paymentMode,
            String advanceAmount,
            String codAmount,
            String claimedMethod,
            String claimedRef) {
        String methodJson = claimedMethod == null ? "null" : "\"" + claimedMethod + "\"";
        String refJson = claimedRef == null ? "null" : "\"" + claimedRef + "\"";
        return """
                {
                  "externalOrderId": "%s",
                  "customerName": "%s",
                  "phone": "%s",
                  "address": "%s",
                  "district": "Dhaka",
                  "lines": [
                    {
                      "sku": "%s",
                      "quantity": %d,
                      "unitPrice": "%s"
                    }
                  ],
                  "shippingFee": "%s",
                  "discountTotal": "%s",
                  "total": "%s",
                  "paymentMode": "%s",
                  "advanceAmount": "%s",
                  "codAmount": "%s",
                  "claimedAdvanceMethod": %s,
                  "claimedAdvanceReference": %s
                }
                """
                .formatted(
                        externalOrderId,
                        customerName,
                        phone,
                        address,
                        sku,
                        quantity,
                        unitPrice,
                        shippingFee,
                        discountTotal,
                        total,
                        paymentMode,
                        advanceAmount,
                        codAmount,
                        methodJson,
                        refJson);
    }

    private record ClientCreds(UUID id, String keyId, String secret) {}
}
