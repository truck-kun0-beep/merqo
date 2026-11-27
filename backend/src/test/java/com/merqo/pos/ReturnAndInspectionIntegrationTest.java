package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merqo.access.AccessContext;
import com.merqo.access.AccountType;
import com.merqo.access.AssignmentType;
import com.merqo.access.LocationAssignment;
import com.merqo.access.LocationAssignmentRepository;
import com.merqo.access.Permission;
import com.merqo.access.PermissionResolver;
import com.merqo.access.PermissionService;
import com.merqo.access.PermissionTemplate;
import com.merqo.access.PermissionTemplateRepository;
import com.merqo.access.UserPermissionOverride;
import com.merqo.access.UserPermissionOverrideRepository;
import com.merqo.catalog.CatalogService;
import com.merqo.catalog.Product;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.VariantCostHistory;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.inventory.InventoryCommand;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.StockBalance;
import com.merqo.inventory.domain.MovementType;
import com.merqo.location.LocationRepository;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;
import com.merqo.user.UserTemplateAssignment;
import com.merqo.user.UserTemplateAssignmentRepository;

/**
 * Integration tests for Slice F: POS returns (partial, full, window/override, cash/bKash session
 * effects, RESTOCK/INSPECTION dispositions) and inspection resolution against PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReturnAndInspectionIntegrationTest {

    private static final String SESSION_COOKIE = "MERQO_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private SaleService saleService;
    @Autowired private LocationRepository locationRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private LocationAssignmentRepository assignmentRepository;
    @Autowired private PermissionTemplateRepository templateRepository;
    @Autowired private UserTemplateAssignmentRepository userTemplateRepository;
    @Autowired private UserPermissionOverrideRepository overrideRepository;
    @Autowired private PermissionService permissionService;
    @Autowired private CatalogService catalogService;
    @Autowired private InventoryService inventoryService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName(
            "partial and full returns refund original method without drift, enforce errors, and update cash")
    void partialFullReturnErrorsAndCashImpact() throws Exception {
        UUID dmId = locationId("DM");
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);

        ProductVariant variant = createVariant(ownerCtx, "RET-A-" + shortId(), "100.00", "60.00");
        inventoryService.receive(
                new InventoryCommand(
                        dmId,
                        variant.getId(),
                        10,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));

        // Manager at DM opens a session, sells 3 units with a 200.00 whole-bill discount?
        // Actually 3 * 100 - 200 = 100.00 paid for 3 units -> partial 1 = 33.33, partial 2 = 33.33,
        // final = 33.34!
        String mgrName = "ret.mgr." + shortId();
        Cookie mgrCookie = createAndSignIn(mgrName, "Pass#1234", "MANAGER", dmId);
        UUID sessionId = openSession(mgrCookie, dmId, "1000.00");

        String saleJson =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(mgrCookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "locationId": "%s",
                                                  "lines": [{"variantId": "%s", "quantity": 3}],
                                                  "billDiscountType": "FIXED",
                                                  "billDiscountValue": "200.00",
                                                  "payment": {"method": "CASH", "tendered": "100.00"}
                                                }
                                                """
                                                        .formatted(dmId, variant.getId())))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.total").value("100.00"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode saleNode = MAPPER.readTree(saleJson);
        UUID saleId = UUID.fromString(saleNode.get("id").asText());
        UUID saleLineId = UUID.fromString(saleNode.get("lines").get(0).get("id").asText());

        // Over-quantity -> 422 RETURN_QUANTITY_EXCEEDED
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(mgrCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "CHANGED_MIND",
                                          "lines": [{"saleLineId": "%s", "quantity": 4, "disposition": "RESTOCK"}]
                                        }
                                        """
                                                .formatted(saleLineId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("RETURN_QUANTITY_EXCEEDED"));

        // OTHER without note -> 422 VALIDATION_FAILED
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(mgrCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "OTHER",
                                          "lines": [{"saleLineId": "%s", "quantity": 1, "disposition": "RESTOCK"}]
                                        }
                                        """
                                                .formatted(saleLineId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // Partial 1: qty 1 -> 33.33
        String idemKey = "ret-idem-" + shortId();
        String retPayload =
                """
                {
                  "reason": "WRONG_SIZE",
                  "lines": [{"saleLineId": "%s", "quantity": 1, "disposition": "RESTOCK"}]
                }
                """
                        .formatted(saleLineId);
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(mgrCookie)
                                .with(CsrfTestSupport.csrf())
                                .header("Idempotency-Key", idemKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(retPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundMethod").value("CASH"))
                .andExpect(jsonPath("$.refundAmount").value("33.33"));

        // Idempotent replay returns 200 with same refundAmount
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(mgrCookie)
                                .with(CsrfTestSupport.csrf())
                                .header("Idempotency-Key", idemKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(retPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value("33.33"));

        // Partial 2: qty 1 -> 33.33
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(mgrCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(retPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundAmount").value("33.33"));

        // Final return: qty 1 -> 33.34 (zero drift! total = 100.00)
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(mgrCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(retPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundAmount").value("33.34"));

        // Further return -> 409 ALREADY_RETURNED
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(mgrCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(retPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALREADY_RETURNED"));

        // Expected cash in session: 1000.00 + 100.00 - 100.00 = 1000.00
        mockMvc.perform(get("/api/v1/pos/sessions/" + sessionId + "/summary").cookie(mgrCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCash").value("1000.00"));

        // GET /returns lists all 3 returns
        mockMvc.perform(get("/api/v1/pos/sales/" + saleId + "/returns").cookie(mgrCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // Stock back to 10 (sold 3, restocked 3)
        assertEquals(10, inventoryService.balanceOf(dmId, variant.getId()).getOnHand());
    }

    @Test
    @DisplayName(
            "enforces return window expiry/override, bKash cash neutrality, and inspection resolve")
    void returnWindowAndInspectionFlow() throws Exception {
        UUID dmId = locationId("DM");
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);

        ProductVariant variant =
                createVariant(ownerCtx, "RET-INSP-" + shortId(), "500.00", "300.00");
        inventoryService.receive(
                new InventoryCommand(
                        dmId,
                        variant.getId(),
                        5,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));

        // Create a staff refunder WITHOUT POS_DISCOUNT_APPROVE at DM, and an approver manager WITH
        // it
        String refunderName = "ret.staff." + shortId();
        Cookie refunderCookie = createAndSignIn(refunderName, "Pass#1234", "CASHIER", dmId);
        AppUser refunderUser = userRepository.findByUsernameIgnoreCase(refunderName).orElseThrow();
        overrideRepository.saveAndFlush(
                new UserPermissionOverride(
                        UUID.randomUUID(),
                        refunderUser.getId(),
                        Permission.POS_REFUND.name(),
                        PermissionResolver.OverrideEffect.ALLOW,
                        dmId,
                        owner.getId(),
                        Instant.now()));

        String mgrName = "ret.m2." + shortId();
        Cookie mgrCookie = createAndSignIn(mgrName, "Pass#1234", "MANAGER", dmId);
        openSession(mgrCookie, dmId, "500.00");
        AppUser mgrUser = userRepository.findByUsernameIgnoreCase(mgrName).orElseThrow();
        AccessContext mgrCtx = permissionService.buildContext(mgrUser);

        // Back-dated bKash sale 40 days ago (outside 30-day window) via internal demo helper
        Instant oldSaleInstant = Instant.now().minus(40, ChronoUnit.DAYS);
        SaleService.SaleCreationOutcome oldOutcome =
                saleService.createSaleForDemo(
                        mgrCtx,
                        new SaleService.CreateSaleCommand(
                                dmId,
                                java.util.List.of(
                                        new SaleService.SaleLineCommand(
                                                variant.getId(), 2, null, null)),
                                null,
                                null,
                                null,
                                new SaleService.PaymentCommand("BKASH", null, "TRXOLD" + shortId()),
                                null,
                                null),
                        oldSaleInstant,
                        "test-old-" + shortId());
        UUID oldSaleId = UUID.fromString(oldOutcome.body().get("id").toString());
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> oldLines =
                (java.util.List<java.util.Map<String, Object>>) oldOutcome.body().get("lines");
        UUID oldLineId = UUID.fromString(oldLines.get(0).get("id").toString());

        // Staff without POS_DISCOUNT_APPROVE gets 409 RETURN_WINDOW_EXPIRED
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + oldSaleId + "/returns")
                                .cookie(refunderCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "DEFECTIVE",
                                          "lines": [{"saleLineId": "%s", "quantity": 2, "disposition": "INSPECTION"}]
                                        }
                                        """
                                                .formatted(oldLineId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RETURN_WINDOW_EXPIRED"));

        // Manager WITH POS_DISCOUNT_APPROVE overrides window; bKash refund goes to INSPECTION
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + oldSaleId + "/returns")
                                .cookie(mgrCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "DEFECTIVE",
                                          "lines": [{"saleLineId": "%s", "quantity": 2, "disposition": "INSPECTION"}]
                                        }
                                        """
                                                .formatted(oldLineId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundMethod").value("BKASH"))
                .andExpect(jsonPath("$.refundAmount").value("1000.00"));

        // Expected cash in manager's session is STILL 500.00 (bKash refund did NOT lower expected
        // cash)
        UUID sessionId =
                UUID.fromString(
                        MAPPER.readTree(
                                        mockMvc.perform(
                                                        get("/api/v1/pos/sessions/current")
                                                                .param(
                                                                        "locationId",
                                                                        dmId.toString())
                                                                .cookie(mgrCookie))
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .get("session")
                                .get("id")
                                .asText());
        mockMvc.perform(get("/api/v1/pos/sessions/" + sessionId + "/summary").cookie(mgrCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCash").value("500.00"));

        // Stock: 3 onHand (5 - 2), 2 inInspection (NOT sellable!)
        StockBalance bal = inventoryService.balanceOf(dmId, variant.getId());
        assertEquals(3, bal.getOnHand());
        assertEquals(2, bal.getInInspection());

        // Visible in GET /inventory/inspections
        String inspList =
                mockMvc.perform(
                                get("/api/v1/inventory/inspections")
                                        .param("locationId", dmId.toString())
                                        .cookie(mgrCookie))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertTrue(inspList.contains(variant.getSku()));

        // Manager without INVENTORY_ADJUST gets 403 on resolve; owner resolves 1 RESTOCK and 1
        // WRITE_OFF
        mockMvc.perform(
                        post("/api/v1/inventory/inspections/resolve")
                                .cookie(mgrCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"locationId":"%s","variantId":"%s","quantity":1,"outcome":"RESTOCK"}
                                        """
                                                .formatted(dmId, variant.getId())))
                .andExpect(status().isForbidden());

        Cookie ownerCookie = signInDemo("owner");
        mockMvc.perform(
                        post("/api/v1/inventory/inspections/resolve")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"locationId":"%s","variantId":"%s","quantity":1,"outcome":"RESTOCK","note":"Good"}
                                        """
                                                .formatted(dmId, variant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(4))
                .andExpect(jsonPath("$.inInspection").value(1));

        mockMvc.perform(
                        post("/api/v1/inventory/inspections/resolve")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"locationId":"%s","variantId":"%s","quantity":1,"outcome":"WRITE_OFF","note":"Torn"}
                                        """
                                                .formatted(dmId, variant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(4))
                .andExpect(jsonPath("$.inInspection").value(0));

        Integer resolvedAudits =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE action = 'INSPECTION_RESOLVED' AND entity_id = ?",
                        Integer.class,
                        variant.getId().toString());
        assertEquals(2, resolvedAudits);
    }

    private ProductVariant createVariant(
            AccessContext ownerCtx, String code, String price, String cost) {
        Product product =
                catalogService.createProduct(
                        ownerCtx, code, "Product " + code, null, null, null, null);
        ProductVariant variant =
                catalogService.createVariant(
                        ownerCtx,
                        product,
                        code + "-V1",
                        "Red",
                        "S",
                        null,
                        new BigDecimal(price),
                        "66" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        catalogService.changeCost(
                ownerCtx,
                variant,
                new BigDecimal(cost),
                VariantCostHistory.SourceType.MANUAL,
                null);
        return variant;
    }

    private UUID openSession(Cookie cookie, UUID locationId, String openingCash) throws Exception {
        String json =
                mockMvc.perform(
                                post("/api/v1/pos/sessions")
                                        .cookie(cookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + locationId
                                                        + "\",\"openingCash\":\""
                                                        + openingCash
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(MAPPER.readTree(json).get("id").asText());
    }

    private Cookie createAndSignIn(
            String username, String rawPassword, String templateCode, UUID locationId)
            throws Exception {
        Instant now = Instant.now();
        AppUser user =
                new AppUser(
                        UUID.randomUUID(),
                        username,
                        null,
                        username,
                        passwordEncoder.encode(rawPassword),
                        AccountType.STAFF,
                        now);
        userRepository.saveAndFlush(user);
        assignmentRepository.saveAndFlush(
                new LocationAssignment(
                        UUID.randomUUID(),
                        user.getId(),
                        locationId,
                        AssignmentType.STAFF,
                        null,
                        now));
        PermissionTemplate template = templateRepository.findByCode(templateCode).orElseThrow();
        userTemplateRepository.saveAndFlush(
                new UserTemplateAssignment(user.getId(), template.getId(), null, now));

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"identifier\":\""
                                                        + username
                                                        + "\",\"password\":\""
                                                        + rawPassword
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(cookie);
        return cookie;
    }

    private Cookie signInDemo(String accountKey) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"" + accountKey + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(cookie);
        return cookie;
    }

    private UUID locationId(String code) {
        return locationRepository.findByCodeIgnoreCase(code).orElseThrow().getId();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
