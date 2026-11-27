package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import com.merqo.access.ScopeTestSupport;
import com.merqo.access.UserPermissionOverrideRepository;
import com.merqo.catalog.CatalogService;
import com.merqo.catalog.Category;
import com.merqo.catalog.Product;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.VariantCostHistory;
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
 * Shared fixture base class for Phase 3B POS integration tests on real PostgreSQL ({@code
 * merqo_test}). Never creates locations (Phase 1 asserts 4); creates unique category, product,
 * variant, barcode, stock, and isolated users per test.
 */
public abstract class PosIntegrationSupport extends ScopeTestSupport {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final Set<String> FORBIDDEN_COST_KEYS =
            Set.of(
                    "cost",
                    "costPrice",
                    "unitCost",
                    "unitCostSnapshot",
                    "valueAtCost",
                    "margin",
                    "profit",
                    "belowCost",
                    "belowCostWarnings");

    @Autowired protected LocationRepository locationRepository;
    @Autowired protected AppUserRepository userRepository;
    @Autowired protected LocationAssignmentRepository assignmentRepository;
    @Autowired protected PermissionTemplateRepository templateRepository;
    @Autowired protected UserTemplateAssignmentRepository userTemplateRepository;
    @Autowired protected UserPermissionOverrideRepository overrideRepository;
    @Autowired protected PermissionService permissionService;
    @Autowired protected CatalogService catalogService;
    @Autowired protected PromotionService promotionService;
    @Autowired protected InventoryService inventoryService;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JdbcTemplate jdbcTemplate;

    protected record ProductFixture(
            Category category, Product product, ProductVariant variant, String barcode) {}

    protected UUID locationId(String code) {
        return locationRepository.findByCodeIgnoreCase(code).orElseThrow().getId();
    }

    protected AppUser demoOwner() {
        return userRepository.findByDemoKey("owner").orElseThrow();
    }

    protected AccessContext ownerContext() {
        return permissionService.buildContext(demoOwner());
    }

    protected ProductFixture createProductFixture(String prefix, String price, String cost) {
        AccessContext ownerCtx = ownerContext();
        String suffix = shortId();
        Category category =
                catalogService.createCategory(ownerCtx, prefix + " Cat " + suffix, null);
        Product product =
                catalogService.createProduct(
                        ownerCtx,
                        prefix + "-" + suffix,
                        prefix + " Product " + suffix,
                        category.getId(),
                        null,
                        null,
                        null);
        String barcode = "91" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ProductVariant variant =
                catalogService.createVariant(
                        ownerCtx,
                        product,
                        prefix + "-SKU-" + suffix,
                        "Black",
                        "M",
                        null,
                        new BigDecimal(price),
                        barcode);
        catalogService.changeCost(
                ownerCtx,
                variant,
                new BigDecimal(cost),
                VariantCostHistory.SourceType.MANUAL,
                null);
        return new ProductFixture(category, product, variant, barcode);
    }

    protected void addOpeningStock(UUID locationId, UUID variantId, int quantity) {
        inventoryService.receive(
                new InventoryCommand(
                        locationId,
                        variantId,
                        quantity,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        demoOwner().getId(),
                        null));
    }

    protected Cookie createAndSignIn(
            String usernamePrefix,
            AccountType accountType,
            String templateCode,
            UUID locationId,
            BigDecimal maxDiscountPercent)
            throws Exception {
        String username = usernamePrefix + "." + shortId();
        String rawPassword = "Pass#1234";
        Instant now = Instant.now();
        AppUser user =
                new AppUser(
                        UUID.randomUUID(),
                        username,
                        null,
                        "User " + username,
                        passwordEncoder.encode(rawPassword),
                        accountType,
                        now);
        if (maxDiscountPercent != null) {
            user.setMaxDiscountPercent(maxDiscountPercent);
        }
        userRepository.saveAndFlush(user);
        assignmentRepository.saveAndFlush(
                new LocationAssignment(
                        UUID.randomUUID(),
                        user.getId(),
                        locationId,
                        accountType == AccountType.MANAGER
                                ? AssignmentType.MANAGER
                                : AssignmentType.STAFF,
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

    protected UUID openSession(Cookie cookie, UUID locationId, String openingCash)
            throws Exception {
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

    protected void assertNoForbiddenCostKeys(JsonNode node, String contextLabel) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                assertFalse(
                        FORBIDDEN_COST_KEYS.contains(key),
                        "Forbidden cost key '" + key + "' found in " + contextLabel);
                assertNoForbiddenCostKeys(node.get(key), contextLabel + "." + key);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertNoForbiddenCostKeys(child, contextLabel + "[]");
            }
        }
    }

    protected static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
