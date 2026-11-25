package com.merqo.promotion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merqo.access.ScopeTestSupport;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;

/**
 * The integration test of the discounts.
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test}. What it proves:
 *
 * <ol>
 *   <li>A promotion that fights the promotions that are already there is refused with 409 and the
 *       code {@code PROMOTION_CONFLICT}, and the answer names every conflicting promotion with the
 *       variants they share and the SKUs of those variants.
 *   <li>The caller may end the conflicting promotions instead, and then the new promotion is
 *       created.
 *   <li>A promotion that fights nobody is created.
 * </ol>
 *
 * <p>The test writes its own catalogue rows and its own promotions, so it never disturbs the seeded
 * demo rows and never disturbs another test.
 */
class PromotionIntegrationTest extends ScopeTestSupport {

    /** The prefix of every SKU this test writes, so the rows are easy to recognise. */
    private static final String SKU_PREFIX = "TEST-PROMO-";

    /** The code of the seeded branch the promotions cover. */
    private static final String BRANCH_CODE = "DM";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private LocationRepository locationRepository;

    @Test
    @DisplayName("a fighting promotion is refused with the conflicts and their SKUs")
    void aFightingPromotionIsRefusedWithTheConflictsAndTheirSkus() throws Exception {
        Cookie owner = signIn("owner");
        String sku = uniqueSku();
        UUID variantId = createVariant(owner, sku);
        String locationId = location(BRANCH_CODE).getId().toString();

        // The first promotion covers the variant at the branch.
        createPromotion(owner, "First promotion", variantId, locationId);

        // The second one wants the same variant at the same place with the same channel, so the
        // two fight. The answer names the promotion that is in the way and the goods they share.
        mockMvc.perform(
                        post("/api/v1/promotions")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        promotionBody(
                                                "Second promotion", variantId, locationId, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROMOTION_CONFLICT"))
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].name").value("First promotion"))
                .andExpect(jsonPath("$.conflicts[0].id").isNotEmpty())
                .andExpect(jsonPath("$.conflicts[0].variantIds[0]").value(variantId.toString()))
                .andExpect(jsonPath("$.conflicts[0].skus[0]").value(sku));
    }

    @Test
    @DisplayName("the caller may end the conflicting promotions instead of refusing")
    void theCallerMayEndTheConflictingPromotionsInsteadOfRefusing() throws Exception {
        Cookie owner = signIn("owner");
        UUID variantId = createVariant(owner, uniqueSku());
        String locationId = location(BRANCH_CODE).getId().toString();

        String firstId = createPromotion(owner, "First promotion", variantId, locationId);

        // The same fight, but this time the caller asks for the old promotions to be ended.
        mockMvc.perform(
                        post("/api/v1/promotions")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        promotionBody(
                                                "Second promotion",
                                                variantId,
                                                locationId,
                                                "\"END_EXISTING\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Second promotion"));

        // The first promotion is ended, so the second one is the only live one. The row is looked
        // up by its id, because the list holds the rows of the other test as well.
        MvcResult list =
                mockMvc.perform(get("/api/v1/promotions").cookie(owner))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode promotions = readJson(list);
        String status = null;
        for (JsonNode promotion : promotions) {
            if (firstId.equals(promotion.get("id").asText())) {
                status = promotion.get("status").asText();
            }
        }
        assertEquals("EXPIRED", status, "The promotion that was in the way has to be ended");
    }

    @Test
    @DisplayName("a promotion that fights nobody is created")
    void aPromotionThatFightsNobodyIsCreated() throws Exception {
        Cookie owner = signIn("owner");
        UUID variantId = createVariant(owner, uniqueSku());
        String locationId = location(BRANCH_CODE).getId().toString();

        mockMvc.perform(
                        post("/api/v1/promotions")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        promotionBody(
                                                "Peaceful promotion", variantId, locationId, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.variantIds[0]").value(variantId.toString()));
    }

    @Test
    @DisplayName("a promotion for the online channel needs the owner")
    void aPromotionForTheOnlineChannelNeedsTheOwner() throws Exception {
        Cookie manager = signIn("manager-dm");
        UUID variantId = createVariant(signIn("owner"), uniqueSku());
        String locationId = location(BRANCH_CODE).getId().toString();

        // A branch manager may only make a promotion for their own branches and only for the
        // point of sale channel.
        mockMvc.perform(
                        post("/api/v1/promotions")
                                .cookie(manager)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        promotionBody(
                                                        "Online promotion",
                                                        variantId,
                                                        locationId,
                                                        null)
                                                .replace(
                                                        "\"scope\":\"POS\"",
                                                        "\"scope\":\"ONLINE\"")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * The body of a promotion request.
     *
     * @param name the name of the promotion
     * @param variantId the variant the promotion covers
     * @param locationId the location the promotion covers
     * @param conflictAction the conflict action, or {@code null} for the default one
     * @return the JSON body
     */
    private String promotionBody(
            String name, UUID variantId, String locationId, String conflictAction) {
        StringBuilder body = new StringBuilder();
        body.append("{\"name\":\"")
                .append(name)
                .append("\",\"discountType\":\"PERCENT\",\"value\":\"10\",\"scope\":\"POS\"")
                .append(",\"allLocations\":false,\"locationIds\":[\"")
                .append(locationId)
                .append("\"],\"variantIds\":[\"")
                .append(variantId)
                .append("\"]");
        if (conflictAction != null) {
            body.append(",\"conflictAction\":").append(conflictAction);
        }
        body.append("}");
        return body.toString();
    }

    /**
     * Creates a promotion and refuses the test when it does not go through.
     *
     * @param owner the session of the owner
     * @param name the name of the promotion
     * @param variantId the variant the promotion covers
     * @param locationId the location the promotion covers
     * @return the id of the new promotion
     * @throws Exception when the request fails
     */
    private String createPromotion(Cookie owner, String name, UUID variantId, String locationId)
            throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/promotions")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(promotionBody(name, variantId, locationId, null)))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readJson(result).get("id").asText();
    }

    /**
     * Creates a product with one active variant and returns the id of the variant.
     *
     * @param owner the session of the owner
     * @param sku the unique SKU of the variant
     * @return the id of the new variant
     * @throws Exception when a request fails
     */
    private UUID createVariant(Cookie owner, String sku) throws Exception {
        MvcResult product =
                mockMvc.perform(
                                post("/api/v1/catalog/products")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"productCode\":\""
                                                        + sku
                                                        + "\",\"name\":\"Test product\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String productId = readJson(product).get("id").asText();

        MvcResult variant =
                mockMvc.perform(
                                post("/api/v1/catalog/products/{id}/variants", productId)
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"sku\":\""
                                                        + sku
                                                        + "\",\"sellingPrice\":\"500\",\"barcode\":\""
                                                        + uniqueBarcode()
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return UUID.fromString(readJson(variant).get("id").asText());
    }

    /**
     * A location of the demo seed.
     *
     * @param code the code of the location
     * @return the location
     */
    private Location location(String code) {
        return locationRepository.findByCodeIgnoreCase(code).orElseThrow();
    }

    /**
     * A SKU that no other row of this test uses.
     *
     * @return the SKU
     */
    private static String uniqueSku() {
        return SKU_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /**
     * A barcode that no other row of this test uses.
     *
     * @return the barcode
     */
    private static String uniqueBarcode() {
        return "89" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    /**
     * Reads the body of an answer as a JSON tree.
     *
     * @param result the answer of the request
     * @return the tree
     * @throws Exception when the body cannot be read
     */
    private static JsonNode readJson(MvcResult result) throws Exception {
        JsonNode node = MAPPER.readTree(result.getResponse().getContentAsString());
        assertTrue(node != null && !node.isMissingNode(), "The answer must carry a JSON body");
        return node;
    }
}
