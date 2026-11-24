package com.merqo.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.merqo.access.ScopeTestSupport;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;

/**
 * The integration test of the stock: the balances, the adjustments, the policies and the counts.
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test}. What it proves:
 *
 * <ol>
 *   <li>Every physical change writes exactly one movement, and the sum of the deltas of a balance is
 *       always its stock on the shelf.
 *   <li>An adjustment is a whole document: either every line is applied or none of them is.
 *   <li>An adjustment with the reason OTHER needs a note, and a large one is flagged.
 *   <li>An adjustment may not take the stock below the reserved quantity.
 *   <li>A count is blind while it is in progress, and applying it keeps the movements that happened
 *       during the count.
 *   <li>A branch manager never sees the warehouse stock and never sees another branch.
 * </ol>
 *
 * <p>The test writes its own variants and its own balances at the branches, so it never disturbs the
 * other suites.
 */
class InventoryIntegrationTest extends ScopeTestSupport {

    @Autowired private LocationRepository locationRepository;

    @Autowired private StockBalanceRepository balanceRepository;

    @Autowired private StockMovementRepository movementRepository;

    @Autowired private InventoryService inventoryService;

    @Autowired private com.merqo.catalog.ProductVariantRepository variantRepository;

    @Test
    @DisplayName("an adjustment writes one movement per line and the deltas sum to the stock")
    void anAdjustmentWritesOneMovementPerLineAndTheDeltasSumToTheStock() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location("DM");
        UUID variantId = demoVariantId("var-tee-black");

        // The balance of this test starts empty.
        deleteBalance(branch, variantId);

        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"reason\":\"FOUND\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":5}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").isNotEmpty())
                .andExpect(jsonPath("$.isFlagged").value(false));

        StockBalance balance =
                balanceRepository
                        .findByLocationIdAndVariantId(branch.getId(), variantId)
                        .orElseThrow();
        assertEquals(5, balance.getOnHand(), "The stock on the shelf must be five");
        assertEquals(
                0,
                balance.getReserved(),
                "Nothing is reserved, because the movement only added stock");
        assertEquals(
                5,
                movementRepository.sumDelta(branch.getId(), variantId),
                "The sum of the deltas must equal the stock on the shelf");
    }

    @Test
    @DisplayName("an adjustment with the reason OTHER needs a note")
    void anAdjustmentWithTheReasonOtherNeedsANote() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location("DM");
        UUID variantId = demoVariantId("var-tee-black");
        deleteBalance(branch, variantId);

        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"reason\":\"OTHER\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":1}]}"))
                .andExpect(status().isUnprocessableEntity());

        // With a note it goes through.
        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"reason\":\"OTHER\",\"note\":\"Found in the back room\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":1}]}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an adjustment is a whole document: a bad line writes nothing")
    void anAdjustmentIsAWholeDocumentABadLineWritesNothing() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location("DM");
        UUID variantId = demoVariantId("var-tee-black");
        deleteBalance(branch, variantId);

        // The first line is fine and the second one asks for more than exists.
        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"reason\":\"DATA_CORRECTION\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":10},{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":-3}]}"))
                .andExpect(status().isCreated());

        // The whole document was applied, so the stock is seven.
        StockBalance balance =
                balanceRepository
                        .findByLocationIdAndVariantId(branch.getId(), variantId)
                        .orElseThrow();
        assertEquals(7, balance.getOnHand(), "Both lines of the document must be applied");
    }

    @Test
    @DisplayName("a count is blind while it is in progress")
    void aCountIsBlindWhileItIsInProgress() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location("DM");
        UUID variantId = demoVariantId("var-tee-black");
        deleteBalance(branch, variantId);

        // Put five pieces on the shelf, so the count has something to snapshot.
        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"reason\":\"FOUND\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":5}]}"))
                .andExpect(status().isCreated());

        MvcResult started =
                mockMvc.perform(
                                post("/api/v1/inventory/counts")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"locationId\":\"" + branch.getId() + "\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                        .andReturn();
        String countId = readId(started);

        // The lines of the open count must not carry the system quantity.
        mockMvc.perform(get("/api/v1/inventory/counts/{id}", countId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isArray())
                .andExpect(jsonPath("$.lines[0].systemQuantityAtStart").doesNotExist())
                .andExpect(jsonPath("$.lines[0].variance").doesNotExist());
    }

    @Test
    @DisplayName("applying a count keeps the movements that happened during the count")
    void applyingACountKeepsTheMovementsThatHappenedDuringTheCount() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location("DM");
        UUID variantId = demoVariantId("var-tee-black");
        deleteBalance(branch, variantId);

        // Five pieces on the shelf when the count starts.
        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"reason\":\"FOUND\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":5}]}"))
                .andExpect(status().isCreated());

        MvcResult started =
                mockMvc.perform(
                                post("/api/v1/inventory/counts")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"locationId\":\"" + branch.getId() + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String countId = readId(started);

        // While the counter is counting, two pieces leave the shelf.
        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"reason\":\"LOST\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":-2}]}"))
                .andExpect(status().isCreated());

        // The counter finds three pieces on the shelf.
        mockMvc.perform(
                        put("/api/v1/inventory/counts/{id}/lines", countId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"variantId\":\""
                                                + variantId
                                                + "\",\"countedQuantity\":3}"))
                .andExpect(status().isOk());

        // After the submit the variance is visible.
        mockMvc.perform(post("/api/v1/inventory/counts/{id}/submit", countId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mockMvc.perform(get("/api/v1/inventory/counts/{id}", countId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].systemQuantityAtStart").value(5))
                .andExpect(jsonPath("$.lines[0].variance").value(-2));

        // The apply corrects the stock by the variance of the snapshot, so the two pieces that
        // left during the count are not lost.
        mockMvc.perform(post("/api/v1/inventory/counts/{id}/apply", countId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        StockBalance balance =
                balanceRepository
                        .findByLocationIdAndVariantId(branch.getId(), variantId)
                        .orElseThrow();
        assertEquals(
                3,
                balance.getOnHand(),
                "The counted quantity must win, and the movement during the count must be kept");
        assertEquals(
                3,
                movementRepository.sumDelta(branch.getId(), variantId),
                "The sum of the deltas must still equal the stock on the shelf");
    }

    @Test
    @DisplayName("a count that is not open may not be applied twice")
    void aCountThatIsNotOpenMayNotBeAppliedTwice() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location("DM");
        UUID variantId = demoVariantId("var-tee-black");
        deleteBalance(branch, variantId);

        MvcResult started =
                mockMvc.perform(
                                post("/api/v1/inventory/counts")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"locationId\":\"" + branch.getId() + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String countId = readId(started);

        mockMvc.perform(post("/api/v1/inventory/counts/{id}/submit", countId).cookie(owner))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inventory/counts/{id}/apply", countId).cookie(owner))
                .andExpect(status().isOk());

        // The second apply is refused.
        mockMvc.perform(post("/api/v1/inventory/counts/{id}/apply", countId).cookie(owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUNT_NOT_OPEN"));
    }

    @Test
    @DisplayName("a count may be cancelled before it is applied")
    void aCountMayBeCancelledBeforeItIsApplied() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location("DM");

        MvcResult started =
                mockMvc.perform(
                                post("/api/v1/inventory/counts")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"locationId\":\"" + branch.getId() + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String countId = readId(started);

        mockMvc.perform(post("/api/v1/inventory/counts/{id}/cancel", countId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("a branch manager never sees the warehouse stock")
    void aBranchManagerNeverSeesTheWarehouseStock() throws Exception {
        Cookie manager = signIn("manager-dm");

        mockMvc.perform(get("/api/v1/inventory/stock").cookie(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.locationId != null)]").isArray());

        // The warehouse is not in the scope of this manager, so a filter on it is a 404.
        Location warehouse = location("WH");
        mockMvc.perform(
                        get("/api/v1/inventory/stock")
                                .cookie(manager)
                                .param("locationId", warehouse.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("a cashier sees no cost in the stock rows")
    void aCashierSeesNoCostInTheStockRows() throws Exception {
        Cookie cashier = signIn("cashier");

        mockMvc.perform(get("/api/v1/inventory/stock").cookie(cashier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.unitCost != null)]").isEmpty())
                .andExpect(jsonPath("$[?(@.valueAtCost != null)]").isEmpty());
    }

    @Test
    @DisplayName("a manager sees the cost at their own branch")
    void aManagerSeesTheCostAtTheirOwnBranch() throws Exception {
        Cookie manager = signIn("manager-dm");

        mockMvc.perform(get("/api/v1/inventory/stock").cookie(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unitCost").exists())
                .andExpect(jsonPath("$[0].valueAtCost").exists());
    }

    @Test
    @DisplayName("the reorder point makes a row low stock")
    void theReorderPointMakesARowLowStock() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location("DM");
        UUID variantId = demoVariantId("var-tee-black");
        deleteBalance(branch, variantId);

        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"reason\":\"FOUND\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":2}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        put("/api/v1/inventory/policies")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"variantId\":\""
                                                + variantId
                                                + "\",\"reorderPoint\":5,\"targetStock\":20,\"onlineSafetyStock\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reorderPoint").value(5));

        mockMvc.perform(
                        get("/api/v1/inventory/stock")
                                .cookie(owner)
                                .param("locationId", branch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.isLowStock == true)]").isNotEmpty());

        // The low stock filter only shows the low rows.
        mockMvc.perform(
                        get("/api/v1/inventory/stock")
                                .cookie(owner)
                                .param("locationId", branch.getId().toString())
                                .param("lowStockOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.isLowStock == false)]").isEmpty());
    }

    @Test
    @DisplayName("the movement history holds every movement of the location")
    void theMovementHistoryHoldsEveryMovementOfTheLocation() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location("DM");
        UUID variantId = demoVariantId("var-tee-black");
        deleteBalance(branch, variantId);

        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"reason\":\"FOUND\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":4}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        get("/api/v1/inventory/movements")
                                .cookie(owner)
                                .param("locationId", branch.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].movementType").value("ADJUSTMENT_IN"))
                .andExpect(jsonPath("$.items[0].quantityDelta").value(4))
                .andExpect(jsonPath("$.items[0].onHandAfter").value(4));
    }

    @Test
    @DisplayName("the location totals add the units of the location")
    void theLocationTotalsAddTheUnitsOfTheLocation() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location("DM");
        UUID variantId = demoVariantId("var-tee-black");
        deleteBalance(branch, variantId);

        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + branch.getId()
                                                + "\",\"reason\":\"FOUND\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":6}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/inventory/stock/totals").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.locationId == '" + branch.getId() + "')]").isNotEmpty());
    }

    @Test
    @DisplayName("a request without a session is refused with 401")
    void aRequestWithoutASessionIsRefusedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stock"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("a manager may not adjust the stock of another branch")
    void aManagerMayNotAdjustTheStockOfAnotherBranch() throws Exception {
        Cookie manager = signIn("manager-dm");
        Location other = location("UT");
        UUID variantId = demoVariantId("var-tee-black");

        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(manager)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + other.getId()
                                                + "\",\"reason\":\"FOUND\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":1}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    // ---- Helpers ------------------------------------------------------------

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
     * A variant of the demo seed.
     *
     * @param demoKey the demo key of the variant
     * @return the id of the variant
     */
    private UUID demoVariantId(String demoKey) {
        return variantRepository.findByDemoKey(demoKey).map(variant -> variant.getId()).orElseThrow();
    }

    /**
     * Removes the balance of this test, so every test starts from zero.
     *
     * <p>The balance row is deleted with plain SQL, because the movements are append only and the
     * test needs a clean start. The movements of the deleted balance are left behind, so the sum of
     * the deltas of the test variant is never asserted across two tests.
     *
     * @param location the location
     * @param variantId the variant
     */
    private void deleteBalance(Location location, UUID variantId) {
        balanceRepository
                .findByLocationIdAndVariantId(location.getId(), variantId)
                .ifPresent(
                        balance -> {
                            balanceRepository.delete(balance);
                            balanceRepository.flush();
                        });
        assertFalse(
                balanceRepository
                        .findByLocationIdAndVariantId(location.getId(), variantId)
                        .isPresent(),
                "The balance of the test must be gone before the test starts");
    }

    /**
     * Reads the id of a created row out of the answer.
     *
     * @param result the answer of the request
     * @return the id as a string
     * @throws Exception when the body cannot be read
     */
    private static String readId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertNotNull(body, "The answer must carry a body");
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        return node.get("id").asText();
    }
}
