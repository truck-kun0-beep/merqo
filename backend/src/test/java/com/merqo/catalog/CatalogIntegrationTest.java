package com.merqo.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.merqo.access.ScopeTestSupport;
import com.merqo.common.web.CsrfTestSupport;

/**
 * The integration test of the catalogue: the barcodes, the variants and the scan lookup.
 *
 * <p>This test runs against the real PostgreSQL database {@code merqo_test} with the real demo
 * seed. It proves the rules that the whole catalogue rests on:
 *
 * <ol>
 *   <li>A barcode is unique across the primary code and the alternates, because they live in one
 *       table.
 *   <li>A variant may not be active without a primary barcode.
 *   <li>The scan lookup finds a variant by an alternate barcode and by the primary one, and it
 *       never returns a deactivated variant.
 *   <li>An unknown barcode is answered with 404 and the clear code {@code BARCODE_NOT_FOUND}.
 *   <li>The primary barcode of an active variant may not be removed.
 * </ol>
 *
 * <p>The test writes its own rows and never touches the seeded demo rows, so it never disturbs the
 * other suites.
 */
class CatalogIntegrationTest extends ScopeTestSupport {

    /** The prefix of every SKU this test writes, so the rows are easy to recognise. */
    private static final String SKU_PREFIX = "TEST-CAT-";

    @Autowired private ProductVariantRepository variantRepository;

    @Test
    @DisplayName("a barcode is unique across the primary code and the alternates")
    void aBarcodeIsUniqueAcrossThePrimaryCodeAndTheAlternates() throws Exception {
        Cookie owner = signIn("owner");

        String barcode = uniqueBarcode();
        createVariantWithBarcode(owner, barcode);

        // A second variant wants the same code. The answer names the code that owns it.
        mockMvc.perform(
                        post("/api/v1/catalog/products/{id}/variants", productId(owner))
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"sku\":\""
                                                + uniqueSku()
                                                + "\",\"sellingPrice\":\"500\",\"barcode\":\""
                                                + barcode
                                                + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BARCODE_IN_USE"));
    }

    @Test
    @DisplayName("a variant may not be active without a primary barcode")
    void aVariantMayNotBeActiveWithoutAPrimaryBarcode() throws Exception {
        Cookie owner = signIn("owner");

        // A variant that is created without a barcode is saved inactive.
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/catalog/products/{id}/variants", productId(owner))
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"sku\":\""
                                                        + uniqueSku()
                                                        + "\",\"sellingPrice\":\"500\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.isActive").value(false))
                        .andReturn();

        String variantId = readId(result);

        // Activating it is refused, because an active variant needs a barcode.
        mockMvc.perform(
                        post("/api/v1/catalog/variants/{id}/activate", variantId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BARCODE_REQUIRED"));
    }

    @Test
    @DisplayName("the lookup finds a variant by an alternate barcode")
    void theLookupFindsAVariantByAnAlternateBarcode() throws Exception {
        Cookie owner = signIn("owner");

        String primary = uniqueBarcode();
        String alternate = uniqueBarcode();
        String variantId = createVariantWithBarcode(owner, primary);

        mockMvc.perform(
                        post("/api/v1/catalog/variants/{id}/barcodes", variantId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"barcode\":\"" + alternate + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/v1/catalog/lookup")
                                .cookie(owner)
                                .param("barcode", alternate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variant.id").value(variantId))
                .andExpect(jsonPath("$.variant.sku").isNotEmpty())
                .andExpect(jsonPath("$.barcodes.length()").value(2));
    }

    @Test
    @DisplayName("an unknown barcode is answered with 404 and the clear code")
    void anUnknownBarcodeIsAnsweredWith404AndTheClearCode() throws Exception {
        Cookie owner = signIn("owner");

        mockMvc.perform(
                        get("/api/v1/catalog/lookup")
                                .cookie(owner)
                                .param("barcode", "99999999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BARCODE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a deactivated variant is never returned by the lookup")
    void aDeactivatedVariantIsNeverReturnedByTheLookup() throws Exception {
        Cookie owner = signIn("owner");

        String barcode = uniqueBarcode();
        String variantId = createVariantWithBarcode(owner, barcode);

        // The variant is active and the lookup finds it.
        mockMvc.perform(
                        get("/api/v1/catalog/lookup")
                                .cookie(owner)
                                .param("barcode", barcode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variant.id").value(variantId));

        // After the deactivation it is gone.
        mockMvc.perform(
                        post("/api/v1/catalog/variants/{id}/deactivate", variantId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/v1/catalog/lookup")
                                .cookie(owner)
                                .param("barcode", barcode))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BARCODE_NOT_FOUND"));
    }

    @Test
    @DisplayName("the reserved prefix MQ- may never be used as a barcode")
    void theReservedPrefixMayNeverBeUsedAsABarcode() throws Exception {
        Cookie owner = signIn("owner");

        // The rule of the barcode is broken, so the request is refused with 422.
        mockMvc.perform(
                        post("/api/v1/catalog/products/{id}/variants", productId(owner))
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"sku\":\""
                                                + uniqueSku()
                                                + "\",\"sellingPrice\":\"500\",\"barcode\":\"MQ-12345\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BARCODE_INVALID"));
    }

    @Test
    @DisplayName("a duplicate SKU is refused with 409 and the clear code")
    void aDuplicateSkuIsRefusedWith409AndTheClearCode() throws Exception {
        Cookie owner = signIn("owner");

        String sku = uniqueSku();

        mockMvc.perform(
                        post("/api/v1/catalog/products/{id}/variants", productId(owner))
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"sku\":\""
                                                + sku
                                                + "\",\"sellingPrice\":\"500\",\"barcode\":\""
                                                + uniqueBarcode()
                                                + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/api/v1/catalog/products/{id}/variants", productId(owner))
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"sku\":\""
                                                + sku
                                                + "\",\"sellingPrice\":\"500\",\"barcode\":\""
                                                + uniqueBarcode()
                                                + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SKU_IN_USE"));
    }

    @Test
    @DisplayName("a price below the cost is accepted and the warning comes back")
    void aPriceBelowTheCostIsAcceptedAndTheWarningComesBack() throws Exception {
        Cookie owner = signIn("owner");

        String variantId = createVariantWithBarcode(owner, uniqueBarcode());

        // A cost of 400 and a price of 100: the price is below the cost.
        mockMvc.perform(
                        post("/api/v1/catalog/variants/{id}/cost", variantId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"costPrice\":\"400\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/catalog/variants/{id}/price", variantId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sellingPrice\":\"100\"}"))
                .andExpect(status().isOk());

        // The price preview flags the line as below cost.
        mockMvc.perform(
                        get("/api/v1/catalog/variants/{id}/price-preview", variantId)
                                .cookie(owner)
                                .param("quantity", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.belowCost").value(true))
                .andExpect(jsonPath("$.cost").value("400.00"));
    }

    @Test
    @DisplayName("the price change is written to the variant and to the history")
    void thePriceChangeIsWrittenToTheVariantAndToTheHistory() throws Exception {
        Cookie owner = signIn("owner");

        String variantId = createVariantWithBarcode(owner, uniqueBarcode());

        mockMvc.perform(
                        post("/api/v1/catalog/variants/{id}/price", variantId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sellingPrice\":\"888\"}"))
                .andExpect(status().isOk());

        // The current price is on the variant itself.
        ProductVariant variant = variantRepository.findById(UUID.fromString(variantId)).orElseThrow();
        assertEquals(
                0,
                new BigDecimal("888").compareTo(variant.getSellingPrice()),
                "The new price must be on the variant");

        // And the history holds a row for it.
        mockMvc.perform(
                        get("/api/v1/catalog/variants/{id}/price-preview", variantId)
                                .cookie(owner)
                                .param("quantity", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listPrice").value(888.0));
    }

    @Test
    @DisplayName("a category that still has active products may not be deactivated")
    void aCategoryThatStillHasActiveProductsMayNotBeDeactivated() throws Exception {
        Cookie owner = signIn("owner");

        // A category of this test with one active product on it.
        MvcResult category =
                mockMvc.perform(
                                post("/api/v1/catalog/categories")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"name\":\"Test category "
                                                        + UUID.randomUUID().toString().substring(0, 8)
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String categoryId = readId(category);

        mockMvc.perform(
                        post("/api/v1/catalog/products")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"productCode\":\""
                                                + uniqueSku()
                                                + "\",\"name\":\"Test product\",\"categoryId\":\""
                                                + categoryId
                                                + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/api/v1/catalog/categories/{id}/deactivate", categoryId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_IN_USE"));
    }

    @Test
    @DisplayName("a request without a session is refused with 401")
    void aRequestWithoutASessionIsRefusedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("the categories and the brands are listed with their state")
    void theCategoriesAndTheBrandsAreListedWithTheirState() throws Exception {
        Cookie owner = signIn("owner");

        mockMvc.perform(get("/api/v1/catalog/categories").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(get("/api/v1/catalog/brands").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("a manager may read the catalogue but a cashier may not change it")
    void aManagerMayReadTheCatalogueButACashierMayNotChangeIt() throws Exception {
        Cookie manager = signIn("manager-dm");
        Cookie cashier = signIn("cashier");

        // The manager may read the products.
        mockMvc.perform(get("/api/v1/catalog/products").cookie(manager))
                .andExpect(status().isOk());

        // The cashier may read them too, but may not create one.
        mockMvc.perform(get("/api/v1/catalog/products").cookie(cashier))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/catalog/products")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"productCode\":\""
                                                + uniqueSku()
                                                + "\",\"name\":\"Not allowed\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("the primary barcode of an active variant may not be removed")
    void thePrimaryBarcodeOfAnActiveVariantMayNotBeRemoved() throws Exception {
        Cookie owner = signIn("owner");

        String variantId = createVariantWithBarcode(owner, uniqueBarcode());

        MvcResult variant =
                mockMvc.perform(get("/api/v1/catalog/variants/{id}", variantId).cookie(owner))
                        .andExpect(status().isOk())
                        .andReturn();

        String barcodeId = readPrimaryBarcodeId(variant);

        mockMvc.perform(
                        delete("/api/v1/catalog/variants/{id}/barcodes/{barcodeId}", variantId, barcodeId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VARIANT_IN_USE"));
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * Creates a variant with a primary barcode and returns its id.
     *
     * @param owner the session of the owner
     * @param barcode the primary barcode of the variant
     * @return the id of the new variant
     * @throws Exception when the request fails
     */
    private String createVariantWithBarcode(Cookie owner, String barcode) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/catalog/products/{id}/variants", productId(owner))
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"sku\":\""
                                                        + uniqueSku()
                                                        + "\",\"sellingPrice\":\"500\",\"barcode\":\""
                                                        + barcode
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readId(result);
    }

    /**
     * A product that the test may hang its variants on.
     *
     * @param owner the session of the owner
     * @return the id of the product
     * @throws Exception when the request fails
     */
    private String productId(Cookie owner) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/catalog/products")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"productCode\":\""
                                                        + uniqueSku()
                                                        + "\",\"name\":\"Test product\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readId(result);
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
     * A SKU that no other row of this test uses.
     *
     * @return the SKU
     */
    private static String uniqueSku() {
        return SKU_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /**
     * Reads the id of a created row out of the answer.
     *
     * @param result the answer of the request
     * @return the id as a string
     * @throws Exception when the body cannot be read
     */
    private static String readId(MvcResult result) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = readJson(result);
        return node.get("id").asText();
    }

    /**
     * Reads the id of the primary barcode of a variant out of the answer.
     *
     * @param result the answer of the variant endpoint
     * @return the id of the primary barcode
     * @throws Exception when the body cannot be read
     */
    private static String readPrimaryBarcodeId(MvcResult result) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = readJson(result);
        for (com.fasterxml.jackson.databind.JsonNode barcode : node.get("barcodes")) {
            if ("PRIMARY".equals(barcode.get("kind").asText())) {
                return barcode.get("id").asText();
            }
        }
        throw new AssertionError("The variant must carry a primary barcode");
    }

    /**
     * Reads the body of an answer as a JSON tree.
     *
     * @param result the answer of the request
     * @return the tree
     * @throws Exception when the body cannot be read
     */
    private static com.fasterxml.jackson.databind.JsonNode readJson(MvcResult result)
            throws Exception {
        String body = result.getResponse().getContentAsString();
        assertNotNull(body, "The answer must carry a body");
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
    }
}
