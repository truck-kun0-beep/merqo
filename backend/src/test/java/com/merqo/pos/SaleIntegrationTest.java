package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
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
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.Scope;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.inventory.InventoryCommand;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.domain.MovementType;
import com.merqo.location.LocationRepository;
import com.merqo.promotion.PromotionService;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;
import com.merqo.user.UserTemplateAssignment;
import com.merqo.user.UserTemplateAssignmentRepository;

/**
 * Integration tests for POS sales, server-side pricing, stock removal, idempotency, receipts, and
 * reprints against PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SaleIntegrationTest {

    private static final String SESSION_COOKIE = "MERQO_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private LocationRepository locationRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private LocationAssignmentRepository assignmentRepository;
    @Autowired private PermissionTemplateRepository templateRepository;
    @Autowired private UserTemplateAssignmentRepository userTemplateRepository;
    @Autowired private UserPermissionOverrideRepository overrideRepository;
    @Autowired private PermissionService permissionService;
    @Autowired private CatalogService catalogService;
    @Autowired private PromotionService promotionService;
    @Autowired private InventoryService inventoryService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName(
            "happy path drops branch stock only, writes movement, and handles receipt + reprint")
    void happyPathReceiptAndReprint() throws Exception {
        UUID dmId = locationId("DM");
        UUID whId = locationId("WH");
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);

        ProductVariant variant =
                createTestVariant(ownerCtx, "SL-HAPPY-" + shortId(), "500.00", "300.00");
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
        inventoryService.receive(
                new InventoryCommand(
                        whId,
                        variant.getId(),
                        25,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));

        String cashierName = "sl.c1." + shortId();
        Cookie cashier =
                createAndSignInStaff(
                        cashierName, "Pass#1234", "CASHIER", dmId, new BigDecimal("10"));

        // Without open session -> 409 POS_SESSION_REQUIRED
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 2}],
                                          "payment": {"method": "CASH", "tendered": "1500.00"}
                                        }
                                        """
                                                .formatted(dmId, variant.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("POS_SESSION_REQUIRED"));

        openSession(cashier, dmId, "2000.00");

        String saleJson =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(cashier)
                                        .with(CsrfTestSupport.csrf())
                                        .header("Idempotency-Key", "idem-happy-" + shortId())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "locationId": "%s",
                                                  "lines": [{"variantId": "%s", "quantity": 2}],
                                                  "payment": {"method": "CASH", "tendered": "1500.00"}
                                                }
                                                """
                                                        .formatted(dmId, variant.getId())))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.total").value("1000.00"))
                        .andExpect(jsonPath("$.changeGiven").value("500.00"))
                        .andExpect(jsonPath("$.receipt.isDuplicate").value(false))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode saleNode = MAPPER.readTree(saleJson);
        UUID saleId = UUID.fromString(saleNode.get("id").asText());
        String receiptNumber = saleNode.get("receiptNumber").asText();
        assertTrue(receiptNumber.matches("^MQ-\\d{4}-\\d{6}$"));

        // Branch stock dropped to 8, warehouse unchanged at 25
        assertEquals(8, inventoryService.balanceOf(dmId, variant.getId()).getOnHand());
        assertEquals(25, inventoryService.balanceOf(whId, variant.getId()).getOnHand());

        // One SALE movement written
        Integer saleMovements =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM stock_movements WHERE reference_type = 'SALE' AND reference_id = ?",
                        Integer.class,
                        saleId);
        assertEquals(1, saleMovements);

        // GET /receipt does not log a reprint
        mockMvc.perform(get("/api/v1/pos/sales/" + saleId + "/receipt").cookie(cashier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNumber").value(receiptNumber))
                .andExpect(jsonPath("$.isDuplicate").value(false));
        Integer reprints0 =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM receipt_reprints WHERE sale_id = ?",
                        Integer.class,
                        saleId);
        assertEquals(0, reprints0);

        // POST /reprint logs duplicate and increments reprintCount
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/reprint")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDuplicate").value(true))
                .andExpect(jsonPath("$.reprintCount").value(1));
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/reprint")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDuplicate").value(true))
                .andExpect(jsonPath("$.reprintCount").value(2));

        // Cashier with DENY POS_REPRINT gets 403
        AppUser cashierUser = userRepository.findByUsernameIgnoreCase(cashierName).orElseThrow();
        overrideRepository.saveAndFlush(
                new UserPermissionOverride(
                        UUID.randomUUID(),
                        cashierUser.getId(),
                        Permission.POS_REPRINT.name(),
                        PermissionResolver.OverrideEffect.DENY,
                        dmId,
                        owner.getId(),
                        Instant.now()));
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/reprint")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName(
            "ignores tampered client prices, applies POS promo, ignores ONLINE promo, and enforces stock")
    void serverPricingPromotionsAndNoPartialSale() throws Exception {
        UUID dmId = locationId("DM");
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);

        ProductVariant posPromoVar =
                createTestVariant(ownerCtx, "SL-POS-" + shortId(), "1000.00", "500.00");
        ProductVariant onlinePromoVar =
                createTestVariant(ownerCtx, "SL-ONL-" + shortId(), "400.00", "200.00");
        inventoryService.receive(
                new InventoryCommand(
                        dmId,
                        posPromoVar.getId(),
                        5,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));
        inventoryService.receive(
                new InventoryCommand(
                        dmId,
                        onlinePromoVar.getId(),
                        3,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));
        // Reserve 2 of onlinePromoVar so available = 1
        inventoryService.reserve(
                new InventoryCommand(
                        dmId,
                        onlinePromoVar.getId(),
                        2,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));

        promotionService.create(
                ownerCtx,
                new PromotionService.PromotionRequest(
                        "POS 10%",
                        DiscountType.PERCENT,
                        new BigDecimal("10"),
                        Scope.POS,
                        false,
                        Set.of(dmId),
                        Set.of(posPromoVar.getId()),
                        null,
                        null,
                        null,
                        PromotionService.ConflictAction.REFUSE));
        promotionService.create(
                ownerCtx,
                new PromotionService.PromotionRequest(
                        "ONLINE 50%",
                        DiscountType.PERCENT,
                        new BigDecimal("50"),
                        Scope.ONLINE,
                        false,
                        Set.of(dmId),
                        Set.of(onlinePromoVar.getId()),
                        null,
                        null,
                        null,
                        PromotionService.ConflictAction.REFUSE));

        Cookie cashier =
                createAndSignInStaff(
                        "sl.tamper." + shortId(),
                        "Pass#1234",
                        "CASHIER",
                        dmId,
                        new BigDecimal("10"));
        openSession(cashier, dmId, "1000.00");

        // Requesting 2 of onlinePromoVar fails with 409 INSUFFICIENT_STOCK (available=1,
        // reserved=2), and posPromoVar stays at 5 (no partial sale!)
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [
                                            {"variantId": "%s", "quantity": 1},
                                            {"variantId": "%s", "quantity": 2}
                                          ],
                                          "payment": {"method": "CASH", "tendered": "5000.00"}
                                        }
                                        """
                                                .formatted(
                                                        dmId,
                                                        posPromoVar.getId(),
                                                        onlinePromoVar.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"));
        assertEquals(5, inventoryService.balanceOf(dmId, posPromoVar.getId()).getOnHand());

        // Tamper client price/total to 1.00; server computes 900.00 + 400.00 = 1300.00
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [
                                            {"variantId": "%s", "quantity": 1, "unitPrice": "1.00", "lineTotal": "1.00"},
                                            {"variantId": "%s", "quantity": 1, "unitPrice": "1.00", "lineTotal": "1.00"}
                                          ],
                                          "total": "2.00",
                                          "payment": {"method": "CASH", "tendered": "1500.00"}
                                        }
                                        """
                                                .formatted(
                                                        dmId,
                                                        posPromoVar.getId(),
                                                        onlinePromoVar.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.promotionDiscount").value("100.00"))
                .andExpect(jsonPath("$.total").value("1300.00"))
                .andExpect(jsonPath("$.changeGiven").value("200.00"));
    }

    @Test
    @DisplayName(
            "enforces discount limits, approval tokens, global unique TrxID, card PAN rejection, and idempotency")
    void discountsPaymentsAndIdempotency() throws Exception {
        UUID dmId = locationId("DM");
        UUID utId = locationId("UT");
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);

        ProductVariant variant =
                createTestVariant(ownerCtx, "SL-POL-" + shortId(), "1000.00", "400.00");
        inventoryService.receive(
                new InventoryCommand(
                        dmId,
                        variant.getId(),
                        20,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));
        inventoryService.receive(
                new InventoryCommand(
                        utId,
                        variant.getId(),
                        20,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));

        String approverName = "sl.app." + shortId();
        Cookie approver =
                createAndSignInStaff(
                        approverName, "Pass#1234", "MANAGER", dmId, new BigDecimal("100"));
        mockMvc.perform(
                        put("/api/v1/auth/approval-pin")
                                .cookie(approver)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\":\"Pass#1234\",\"pin\":\"6381\"}"))
                .andExpect(status().isNoContent());

        String dmCashierName = "sl.dmc." + shortId();
        Cookie dmCashier =
                createAndSignInStaff(
                        dmCashierName, "Pass#1234", "CASHIER", dmId, new BigDecimal("5"));
        openSession(dmCashier, dmId, "1000.00");

        String utCashierName = "sl.utc." + shortId();
        Cookie utCashier =
                createAndSignInStaff(
                        utCashierName, "Pass#1234", "CASHIER", utId, new BigDecimal("5"));
        openSession(utCashier, utId, "1000.00");

        // Above 5% without token -> 422 DISCOUNT_LIMIT_EXCEEDED
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1, "manualDiscountType": "PERCENT", "manualDiscountValue": "15"}],
                                          "payment": {"method": "CASH", "tendered": "1000.00"}
                                        }
                                        """
                                                .formatted(dmId, variant.getId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("DISCOUNT_LIMIT_EXCEEDED"));

        // Obtain token for 20% and complete sale with idempotency key
        String tokenJson =
                mockMvc.perform(
                                post("/api/v1/pos/approvals")
                                        .cookie(dmCashier)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + dmId
                                                        + "\",\"pin\":\"6381\",\"maxDiscountPercent\":20}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String token = MAPPER.readTree(tokenJson).get("approvalToken").asText();

        String idemKey = "idem-trx-" + shortId();
        String trxId = "trx" + shortId();
        String salePayload =
                """
                {
                  "locationId": "%s",
                  "lines": [{"variantId": "%s", "quantity": 1, "manualDiscountType": "PERCENT", "manualDiscountValue": "15"}],
                  "approvalToken": "%s",
                  "payment": {"method": "BKASH", "reference": "%s"}
                }
                """
                        .formatted(dmId, variant.getId(), token, trxId);

        String firstSale =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(dmCashier)
                                        .with(CsrfTestSupport.csrf())
                                        .header("Idempotency-Key", idemKey)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(salePayload))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.total").value("850.00"))
                        .andExpect(jsonPath("$.payment.reference").value(trxId.toUpperCase()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Idempotent replay with same key & same body -> 200 with same sale id and same stock (19)
        String replaySale =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(dmCashier)
                                        .with(CsrfTestSupport.csrf())
                                        .header("Idempotency-Key", idemKey)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(salePayload))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertEquals(
                MAPPER.readTree(firstSale).get("id").asText(),
                MAPPER.readTree(replaySale).get("id").asText());
        assertEquals(19, inventoryService.balanceOf(dmId, variant.getId()).getOnHand());

        // Same key with changed body -> 409 IDEMPOTENCY_CONFLICT
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmCashier)
                                .with(CsrfTestSupport.csrf())
                                .header("Idempotency-Key", idemKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 2}],
                                          "payment": {"method": "CASH", "tendered": "2000.00"}
                                        }
                                        """
                                                .formatted(dmId, variant.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));

        // Reusing same approval token with a new idempotency key fails with 422 APPROVAL_INVALID
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(dmCashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1, "manualDiscountType": "PERCENT", "manualDiscountValue": "15"}],
                                          "approvalToken": "%s",
                                          "payment": {"method": "CASH", "tendered": "1000.00"}
                                        }
                                        """
                                                .formatted(dmId, variant.getId(), token)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_INVALID"));

        // Global TrxID uniqueness across locations (Uttara tries same bKash TrxID -> 409
        // DUPLICATE_REFERENCE)
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(utCashier)
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
                                                        utId,
                                                        variant.getId(),
                                                        trxId.toLowerCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_REFERENCE"));

        // Card PAN rejected in reference field
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
                                          "payment": {"method": "CARD", "reference": "4111-1111-1111-1111"}
                                        }
                                        """
                                                .formatted(dmId, variant.getId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("CARD_NUMBER_REJECTED"));
    }

    private ProductVariant createTestVariant(
            AccessContext ownerCtx, String code, String price, String cost) {
        Product product =
                catalogService.createProduct(
                        ownerCtx, code, "Product " + code, null, null, null, null);
        ProductVariant variant =
                catalogService.createVariant(
                        ownerCtx,
                        product,
                        code + "-V1",
                        "Black",
                        "M",
                        null,
                        new BigDecimal(price),
                        "88" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        catalogService.changeCost(
                ownerCtx,
                variant,
                new BigDecimal(cost),
                VariantCostHistory.SourceType.MANUAL,
                null);
        return variant;
    }

    private void openSession(Cookie cookie, UUID locationId, String openingCash) throws Exception {
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
                .andExpect(status().isCreated());
    }

    private Cookie createAndSignInStaff(
            String username,
            String rawPassword,
            String templateCode,
            UUID locationId,
            BigDecimal maxDiscountPercent)
            throws Exception {
        Instant now = Instant.now();
        AppUser user =
                new AppUser(
                        UUID.randomUUID(),
                        username,
                        null,
                        "Staff " + username,
                        passwordEncoder.encode(rawPassword),
                        AccountType.STAFF,
                        now);
        user.setMaxDiscountPercent(maxDiscountPercent);
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

    private UUID locationId(String code) {
        return locationRepository.findByCodeIgnoreCase(code).orElseThrow().getId();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
