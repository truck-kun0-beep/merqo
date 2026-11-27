package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
import com.merqo.access.PermissionService;
import com.merqo.access.PermissionTemplate;
import com.merqo.access.PermissionTemplateRepository;
import com.merqo.catalog.CatalogService;
import com.merqo.catalog.Product;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.VariantCostHistory;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.Scope;
import com.merqo.common.time.BusinessTime;
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
 * Integration tests for Slice E: POS sales history, Dhaka-day range filters, pagination, receipt
 * lookup, catalog scan lookup, and cross-branch/cross-cashier scope &amp; tampering checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SaleHistoryAndScopeIntegrationTest {

    private static final String SESSION_COOKIE = "MERQO_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private LocationRepository locationRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private LocationAssignmentRepository assignmentRepository;
    @Autowired private PermissionTemplateRepository templateRepository;
    @Autowired private UserTemplateAssignmentRepository userTemplateRepository;
    @Autowired private PermissionService permissionService;
    @Autowired private CatalogService catalogService;
    @Autowired private PromotionService promotionService;
    @Autowired private InventoryService inventoryService;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName(
            "enforces branch scope, owner-aware cashier visibility, filters, pagination, and catalog scan")
    void historyLookupScopeAndTampering() throws Exception {
        UUID dmId = locationId("DM");
        UUID mpId = locationId("MP");
        UUID utId = locationId("UT");
        UUID whId = locationId("WH");

        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);

        String barcode = "77" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Product product =
                catalogService.createProduct(
                        ownerCtx, "HST-" + shortId(), "History Shirt", null, null, null, null);
        ProductVariant variant =
                catalogService.createVariant(
                        ownerCtx,
                        product,
                        "HST-SKU-" + shortId(),
                        "Blue",
                        "L",
                        null,
                        new BigDecimal("800.00"),
                        barcode);
        catalogService.changeCost(
                ownerCtx,
                variant,
                new BigDecimal("450.00"),
                VariantCostHistory.SourceType.MANUAL,
                null);
        promotionService.create(
                ownerCtx,
                new PromotionService.PromotionRequest(
                        "DM POS Promo",
                        DiscountType.FIXED,
                        new BigDecimal("50.00"),
                        Scope.POS,
                        false,
                        Set.of(dmId),
                        Set.of(variant.getId()),
                        null,
                        null,
                        null,
                        PromotionService.ConflictAction.REFUSE));

        for (UUID loc : List.of(dmId, mpId, utId)) {
            inventoryService.receive(
                    new InventoryCommand(
                            loc,
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
        }

        Cookie cashier1 = createAndSignInStaff("hs.c1." + shortId(), "Pass#1234", "CASHIER", dmId);
        Cookie cashier2 = createAndSignInStaff("hs.c2." + shortId(), "Pass#1234", "CASHIER", dmId);
        Cookie utCashier = createAndSignInStaff("hs.ut." + shortId(), "Pass#1234", "CASHIER", utId);
        Cookie mpCashier = createAndSignInStaff("hs.mp." + shortId(), "Pass#1234", "CASHIER", mpId);

        openSession(cashier1, dmId);
        openSession(cashier2, dmId);
        openSession(utCashier, utId);
        openSession(mpCashier, mpId);

        // Catalog scan as cashier1 at DM returns POS promo and hides cost
        String lookupJson =
                mockMvc.perform(
                                get("/api/v1/catalog/lookup")
                                        .param("barcode", barcode)
                                        .param("locationId", dmId.toString())
                                        .cookie(cashier1))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.netUnitPrice").value(750.0))
                        .andExpect(jsonPath("$.promotion.name").value("DM POS Promo"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode lookupNode = MAPPER.readTree(lookupJson);
        assertFalse(lookupNode.has("cost"));
        assertFalse(lookupNode.get("variant").has("costPrice"));

        // Cashier1 creates 2 sales at DM, Cashier2 creates 1 at DM, MP creates 1 at MP, UT creates
        // 1 at UT
        JsonNode s1a = completeCashSale(cashier1, dmId, variant.getId(), "2000.00");
        JsonNode s1b = completeCashSale(cashier1, dmId, variant.getId(), "2000.00");
        JsonNode s2 = completeCashSale(cashier2, dmId, variant.getId(), "2000.00");
        JsonNode sMp = completeCashSale(mpCashier, mpId, variant.getId(), "2000.00");
        JsonNode sUt = completeCashSale(utCashier, utId, variant.getId(), "2000.00");

        String r1a = s1a.get("receiptNumber").asText();
        String r2 = s2.get("receiptNumber").asText();
        String rMp = sMp.get("receiptNumber").asText();
        String rUt = sUt.get("receiptNumber").asText();

        // Cashier1 sees ONLY their own 2 sales in history, cannot read cashier2's or UT's sale
        String c1List =
                mockMvc.perform(get("/api/v1/pos/sales").cookie(cashier1))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode c1Items = MAPPER.readTree(c1List).get("items");
        assertEquals(2, c1Items.size());

        mockMvc.perform(get("/api/v1/pos/sales/" + s2.get("id").asText()).cookie(cashier1))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        get("/api/v1/pos/sales/lookup")
                                .param("receiptNumber", r2.toLowerCase())
                                .cookie(cashier1))
                .andExpect(status().isNotFound());

        // Cashier1 cannot sell at Mirpur, Uttara, or Warehouse
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(cashier1)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "CASH", "tendered": "1000.00"}
                                        }
                                        """
                                                .formatted(mpId, variant.getId())))
                .andExpect(status().isNotFound());

        Cookie warehouse = signInDemo("warehouse");
        mockMvc.perform(
                        post("/api/v1/pos/sales")
                                .cookie(warehouse)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "locationId": "%s",
                                          "lines": [{"variantId": "%s", "quantity": 1}],
                                          "payment": {"method": "CASH", "tendered": "1000.00"}
                                        }
                                        """
                                                .formatted(whId, variant.getId())))
                .andExpect(status().isForbidden());

        // Manager DM sees DM & MP sales (and lookup works case-insensitively), gets 404 for UT
        Cookie managerDm = signInDemo("manager-dm");
        mockMvc.perform(
                        get("/api/v1/pos/sales/lookup")
                                .param("receiptNumber", r1a.toLowerCase())
                                .cookie(managerDm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNumber").value(r1a));
        mockMvc.perform(
                        get("/api/v1/pos/sales/lookup")
                                .param("receiptNumber", rMp)
                                .cookie(managerDm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNumber").value(rMp));
        mockMvc.perform(
                        get("/api/v1/pos/sales/lookup")
                                .param("receiptNumber", rUt)
                                .cookie(managerDm))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/pos/sales/" + sUt.get("id").asText()).cookie(managerDm))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        get("/api/v1/pos/sales")
                                .param("locationId", utId.toString())
                                .cookie(managerDm))
                .andExpect(status().isNotFound());

        // Dhaka-day date filter + search + pagination
        LocalDate dhakaToday = LocalDate.now(BusinessTime.BUSINESS_ZONE);
        mockMvc.perform(
                        get("/api/v1/pos/sales")
                                .param("locationId", dmId.toString())
                                .param("fromDate", dhakaToday.toString())
                                .param("toDate", dhakaToday.toString())
                                .param("q", r1a)
                                .param("page", "0")
                                .param("size", "5")
                                .cookie(managerDm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].receiptNumber").value(r1a));

        // Past Dhaka day filter returns empty for today's receipt
        LocalDate yesterday = dhakaToday.minusDays(1);
        mockMvc.perform(
                        get("/api/v1/pos/sales")
                                .param("fromDate", yesterday.toString())
                                .param("toDate", yesterday.toString())
                                .param("q", r1a)
                                .cookie(managerDm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        assertTrue(s1b.get("receiptNumber").asText().startsWith("MQ-"));
    }

    private JsonNode completeCashSale(
            Cookie cookie, UUID locationId, UUID variantId, String tendered) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(cookie)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "locationId": "%s",
                                                  "lines": [{"variantId": "%s", "quantity": 1}],
                                                  "payment": {"method": "CASH", "tendered": "%s"}
                                                }
                                                """
                                                        .formatted(
                                                                locationId, variantId, tendered)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return MAPPER.readTree(body);
    }

    private void openSession(Cookie cookie, UUID locationId) throws Exception {
        mockMvc.perform(
                        post("/api/v1/pos/sessions")
                                .cookie(cookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + locationId
                                                + "\",\"openingCash\":\"1000.00\"}"))
                .andExpect(status().isCreated());
    }

    private Cookie createAndSignInStaff(
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
