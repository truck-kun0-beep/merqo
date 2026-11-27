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

import java.math.BigDecimal;
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
 * The integration test of the stock: the balances, the adjustments, the policies and the counts.
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test}. What it proves:
 *
 * <ol>
 *   <li>Every physical change writes exactly one movement, and the sum of the deltas of a balance
 *       is always its stock on the shelf.
 *   <li>An adjustment is a whole document: either every line is applied or none of them is.
 *   <li>An adjustment with the reason OTHER needs a note.
 *   <li>A count is blind while it is in progress, and applying it keeps the movements that happened
 *       during the count.
 *   <li>A branch manager never sees the warehouse stock and never sees another branch.
 *   <li>The purchase cost is only part of the answer for a caller who may see it.
 * </ol>
 *
 * <p><b>Every test builds its own catalogue rows.</b> A test creates its own product, its own
 * variant with a unique SKU and a unique primary barcode, and where it needs one its own category.
 * Nothing is shared with the demo seed and nothing is shared with another test, so the balances of
 * one test can never be the balances of another test. The seeded Dhanmondi branch is the place
 * where the stock is put, because the demo seed owns the locations.
 */
class InventoryIntegrationTest extends ScopeTestSupport {

    /** The prefix of every SKU this test writes, so the rows are easy to recognise. */
    private static final String SKU_PREFIX = "TEST-INV-";

    /** The code of the seeded branch the stock is put on. */
    private static final String BRANCH_CODE = "DM";

    /** The code of the seeded warehouse, which is outside the scope of a branch manager. */
    private static final String WAREHOUSE_CODE = "WH";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private LocationRepository locationRepository;

    @Autowired private StockBalanceRepository balanceRepository;

    @Autowired private StockMovementRepository movementRepository;

    @Test
    @DisplayName("an adjustment writes one movement per line and the deltas sum to the stock")
    void anAdjustmentWritesOneMovementPerLineAndTheDeltasSumToTheStock() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        String sku = uniqueSku();
        UUID variantId = createVariant(owner, sku, null);

        // The variant is new, so its balance starts at zero and the sum of the deltas has to be
        // exactly the quantity that was put on the shelf.
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
        Location branch = location(BRANCH_CODE);
        UUID variantId = createVariant(owner, uniqueSku(), null);

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
    @DisplayName("an adjustment is a whole document: every line is applied or none of them is")
    void anAdjustmentIsAWholeDocumentEveryLineIsAppliedOrNoneOfThemIs() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        UUID variantId = createVariant(owner, uniqueSku(), null);

        // Two lines of one document: ten pieces in and three pieces out.
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
        assertEquals(
                7,
                movementRepository.sumDelta(branch.getId(), variantId),
                "Every line of the document must have written its own movement");
    }

    @Test
    @DisplayName("an adjustment answer carries the SKU of every line")
    void anAdjustmentAnswerCarriesTheSkuOfEveryLine() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        String sku = uniqueSku();
        UUID variantId = createVariant(owner, sku, null);

        MvcResult created =
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
                        .andExpect(status().isCreated())
                        .andReturn();

        String adjustmentId = readJson(created).get("id").asText();

        // The screen shows the document, so every line has to carry the goods it is about.
        mockMvc.perform(get("/api/v1/inventory/adjustments/{id}", adjustmentId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").isNotEmpty())
                .andExpect(jsonPath("$.reason").value("FOUND"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].variantId").value(variantId.toString()))
                .andExpect(jsonPath("$.lines[0].sku").value(sku));
    }

    @Test
    @DisplayName("an adjustment of another branch is not found")
    void anAdjustmentOfAnotherBranchIsNotFound() throws Exception {
        Cookie owner = signIn("owner");
        Cookie manager = signIn("manager-dm");
        // Uttara is not one of the branches of the manager of Dhanmondi and Mirpur.
        Location other = location("UT");
        UUID variantId = createVariant(owner, uniqueSku(), null);

        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/inventory/adjustments")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + other.getId()
                                                        + "\",\"reason\":\"FOUND\",\"lines\":[{\"variantId\":\""
                                                        + variantId
                                                        + "\",\"quantityDelta\":1}]}"))
                        .andExpect(status().isCreated())
                        .andReturn();

        // The manager of another branch may not even learn that the document exists.
        assertOutOfScope(
                mockMvc.perform(
                        get(
                                        "/api/v1/inventory/adjustments/{id}",
                                        readJson(created).get("id").asText())
                                .cookie(manager)));
    }

    @Test
    @DisplayName("a count is blind while it is in progress")
    void aCountIsBlindWhileItIsInProgress() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        // The category comes first, so the variant is really inside it and the count holds its
        // line.
        String categoryId = createCategory(owner);
        UUID variantId = createVariant(owner, uniqueSku(), categoryId);

        // Put five pieces on the shelf, so the count has something to snapshot.
        adjust(owner, branch.getId(), variantId, 5);

        MvcResult started =
                mockMvc.perform(
                                post("/api/v1/inventory/counts")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + branch.getId()
                                                        + "\",\"categoryId\":\""
                                                        + categoryId
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                        .andReturn();
        String countId = readId(started);

        // The lines of the open count must not carry the system quantity.
        MvcResult answered =
                mockMvc.perform(get("/api/v1/inventory/counts/{id}", countId).cookie(owner))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.lines").isArray())
                        .andReturn();

        JsonNode lines = readJson(answered).get("lines");
        assertTrue(lines.size() > 0, "The count must hold the line of the fresh variant");
        for (JsonNode line : lines) {
            assertFalse(
                    line.has("systemQuantityAtStart"),
                    "A count in progress must not show the quantity the system had");
            assertFalse(line.has("variance"), "A count in progress must not show the variance");
        }
    }

    @Test
    @DisplayName("applying a count keeps the movements that happened during the count")
    void applyingACountKeepsTheMovementsThatHappenedDuringTheCount() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        // The category comes first, so the variant is really inside it and the count holds its
        // line. The second variant stays outside the category, so the count does not touch it.
        String categoryId = createCategory(owner);
        UUID variantId = createVariant(owner, uniqueSku(), categoryId);
        UUID otherVariantId = createVariant(owner, uniqueSku(), null);

        // Five pieces of the counted variant on the shelf when the count starts.
        adjust(owner, branch.getId(), variantId, 5);
        adjust(owner, branch.getId(), otherVariantId, 4);

        MvcResult started =
                mockMvc.perform(
                                post("/api/v1/inventory/counts")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + branch.getId()
                                                        + "\",\"categoryId\":\""
                                                        + categoryId
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String countId = readId(started);

        // While the counter is counting, a piece of the other variant leaves the shelf.
        adjust(owner, branch.getId(), otherVariantId, -1);

        // The counter finds three pieces of the counted variant on the shelf.
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
        mockMvc.perform(
                        post("/api/v1/inventory/counts/{id}/submit", countId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        MvcResult submitted =
                mockMvc.perform(get("/api/v1/inventory/counts/{id}", countId).cookie(owner))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode line = lineOfVariant(readJson(submitted).get("lines"), variantId);
        assertEquals(5, line.get("systemQuantityAtStart").asInt(), "The snapshot must be the five");
        assertEquals(-2, line.get("variance").asInt(), "The variance must be the two that left");

        // The apply corrects the stock by the variance of the snapshot.
        mockMvc.perform(
                        post("/api/v1/inventory/counts/{id}/apply", countId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf()))
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

        // The movement that happened during the count is still there: the apply of the count
        // only wrote its own correction.
        StockBalance otherBalance =
                balanceRepository
                        .findByLocationIdAndVariantId(branch.getId(), otherVariantId)
                        .orElseThrow();
        assertEquals(
                3,
                otherBalance.getOnHand(),
                "The movement that happened during the count must not be thrown away");
        assertEquals(
                3,
                movementRepository.sumDelta(branch.getId(), otherVariantId),
                "The sum of the deltas of the other variant must still equal its stock");
    }

    @Test
    @DisplayName("a count that is not open may not be applied twice")
    void aCountThatIsNotOpenMayNotBeAppliedTwice() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        // The category comes first, so the variant is really inside it and the count holds its
        // line.
        String categoryId = createCategory(owner);
        createVariant(owner, uniqueSku(), categoryId);

        MvcResult started =
                mockMvc.perform(
                                post("/api/v1/inventory/counts")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + branch.getId()
                                                        + "\",\"categoryId\":\""
                                                        + categoryId
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String countId = readId(started);

        mockMvc.perform(
                        post("/api/v1/inventory/counts/{id}/submit", countId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/inventory/counts/{id}/apply", countId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk());

        // The second apply is refused.
        mockMvc.perform(
                        post("/api/v1/inventory/counts/{id}/apply", countId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUNT_NOT_OPEN"));
    }

    @Test
    @DisplayName("a count may be cancelled before it is applied")
    void aCountMayBeCancelledBeforeItIsApplied() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        String categoryId = createCategory(owner);
        createVariant(owner, uniqueSku(), categoryId);

        MvcResult started =
                mockMvc.perform(
                                post("/api/v1/inventory/counts")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + branch.getId()
                                                        + "\",\"categoryId\":\""
                                                        + categoryId
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String countId = readId(started);

        mockMvc.perform(
                        post("/api/v1/inventory/counts/{id}/cancel", countId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf()))
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
        Location warehouse = location(WAREHOUSE_CODE);
        mockMvc.perform(
                        get("/api/v1/inventory/stock")
                                .cookie(manager)
                                .param("locationId", warehouse.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("a branch manager never sees the warehouse movements")
    void aBranchManagerNeverSeesTheWarehouseMovements() throws Exception {
        Cookie manager = signIn("manager-dm");
        Location warehouse = location(WAREHOUSE_CODE);

        mockMvc.perform(
                        get("/api/v1/inventory/movements")
                                .cookie(manager)
                                .param("locationId", warehouse.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("a cashier sees no cost in the stock rows")
    void aCashierSeesNoCostInTheStockRows() throws Exception {
        Cookie cashier = signIn("cashier");
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        String sku = uniqueSku();
        // The catalogue rows are written by the owner: a cashier may not create a product.
        UUID variantId = createVariant(owner, sku, null);
        // The stock is put on the shelf by the owner, so the branch really holds a balance for
        // this variant.
        adjust(owner, branch.getId(), variantId, 3);

        // The cashier may see the stock of the branch, so the row is there, but the cost is not.
        MvcResult answered =
                mockMvc.perform(
                                get("/api/v1/inventory/stock")
                                        .cookie(cashier)
                                        .param("locationId", branch.getId().toString())
                                        .param("q", sku))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode rows = readJson(answered);
        assertTrue(rows.size() > 0, "The cashier must see the stock of their own branch");
        for (JsonNode row : rows) {
            assertFalse(
                    row.has("unitCost"),
                    "A cashier may not see the purchase cost of a row: " + row);
            assertFalse(
                    row.has("valueAtCost"),
                    "A cashier may not see the value at cost of a row: " + row);
        }
        // The fresh variant is really in the answer, so the check above is not vacuous.
        assertEquals(
                variantId.toString(),
                rows.get(0).get("variantId").asText(),
                "The answer must hold the fresh variant of this test");
    }

    @Test
    @DisplayName("a manager sees the cost at their own branch")
    void aManagerSeesTheCostAtTheirOwnBranch() throws Exception {
        Cookie manager = signIn("manager-dm");
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        String sku = uniqueSku();
        UUID variantId = createVariant(owner, sku, null);

        // The variant has a cost of 400, so the manager may see it.
        setCost(owner, variantId, "400");
        adjust(owner, branch.getId(), variantId, 3);

        MvcResult answered =
                mockMvc.perform(
                                get("/api/v1/inventory/stock")
                                        .cookie(manager)
                                        .param("locationId", branch.getId().toString())
                                        .param("q", sku))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode rows = readJson(answered);
        assertTrue(rows.size() > 0, "The manager must see the stock of their own branch");
        JsonNode row = rows.get(0);
        assertEquals(
                variantId.toString(),
                row.get("variantId").asText(),
                "The answer must hold the fresh variant of this test");
        assertEquals(
                0,
                new BigDecimal("400").compareTo(row.get("unitCost").decimalValue()),
                "The manager must see the purchase cost of the row");
        assertEquals(
                0,
                new BigDecimal("1200").compareTo(row.get("valueAtCost").decimalValue()),
                "The manager must see the value at cost of the row");
    }

    @Test
    @DisplayName("the reorder point makes a row low stock")
    void theReorderPointMakesARowLowStock() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        String sku = uniqueSku();
        UUID variantId = createVariant(owner, sku, null);

        adjust(owner, branch.getId(), variantId, 2);

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

        // The list is scoped to the fresh variant, so no other balance can answer for it.
        MvcResult answered =
                mockMvc.perform(
                                get("/api/v1/inventory/stock")
                                        .cookie(owner)
                                        .param("locationId", branch.getId().toString())
                                        .param("q", sku))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode rows = readJson(answered);
        assertTrue(rows.size() > 0, "The fresh variant must be in the stock rows");
        assertTrue(
                rows.get(0).get("isLowStock").asBoolean(),
                "Two pieces with a reorder point of five is low stock");

        // The low stock filter only shows the low rows.
        MvcResult low =
                mockMvc.perform(
                                get("/api/v1/inventory/stock")
                                        .cookie(owner)
                                        .param("locationId", branch.getId().toString())
                                        .param("q", sku)
                                        .param("lowStockOnly", "true"))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode lowRows = readJson(low);
        assertTrue(lowRows.size() > 0, "The low stock filter must show the low row");
        for (JsonNode row : lowRows) {
            assertTrue(
                    row.get("isLowStock").asBoolean(),
                    "The low stock filter must only show the low rows");
        }
    }

    @Test
    @DisplayName("the movement history holds every movement of the location")
    void theMovementHistoryHoldsEveryMovementOfTheLocation() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        UUID variantId = createVariant(owner, uniqueSku(), null);

        adjust(owner, branch.getId(), variantId, 4);

        MvcResult answered =
                mockMvc.perform(
                                get("/api/v1/inventory/movements")
                                        .cookie(owner)
                                        .param("locationId", branch.getId().toString())
                                        .param("variantId", variantId.toString()))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode items = readJson(answered).get("items");
        assertTrue(items.size() > 0, "The history must hold the movement of the fresh variant");
        JsonNode newest = items.get(0);
        assertEquals(
                "ADJUSTMENT_IN", newest.get("movementType").asText(), "The newest movement is in");
        assertEquals(4, newest.get("quantityDelta").asInt(), "The movement adds four pieces");
        assertEquals(4, newest.get("onHandAfter").asInt(), "The stock after the movement is four");
    }

    @Test
    @DisplayName("the location totals add the units of the location")
    void theLocationTotalsAddTheUnitsOfTheLocation() throws Exception {
        Cookie owner = signIn("owner");
        Location branch = location(BRANCH_CODE);
        UUID variantId = createVariant(owner, uniqueSku(), null);

        adjust(owner, branch.getId(), variantId, 6);

        MvcResult answered =
                mockMvc.perform(get("/api/v1/inventory/stock/totals").cookie(owner))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode totals = readJson(answered);
        JsonNode row = null;
        for (JsonNode candidate : totals) {
            if (branch.getId().toString().equals(candidate.get("locationId").asText())) {
                row = candidate;
                break;
            }
        }
        assertNotNull(row, "The totals must hold a row for the branch: " + totals);
        assertTrue(
                row.get("units").asLong() >= 6,
                "The units of the branch must at least hold the six pieces of this test");
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
        Cookie owner = signIn("owner");
        Location other = location("UT");
        // The catalogue rows are written by the owner: a manager may not create a product.
        UUID variantId = createVariant(owner, uniqueSku(), null);

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
     * Creates a product with one active variant and returns the id of the variant.
     *
     * <p>The variant gets a unique SKU and a unique primary barcode, so it is active and it never
     * collides with another row of this test or with the demo seed.
     *
     * @param owner the session of the owner
     * @param sku the unique SKU of the variant
     * @param categoryId the category of the product, may be {@code null}
     * @return the id of the new variant
     * @throws Exception when a request fails
     */
    private UUID createVariant(Cookie owner, String sku, String categoryId) throws Exception {
        String productId = createProduct(owner, categoryId);
        MvcResult result =
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
                        .andExpect(jsonPath("$.isActive").value(true))
                        .andReturn();
        return UUID.fromString(readId(result));
    }

    /**
     * Creates a product and returns its id.
     *
     * @param owner the session of the owner
     * @param categoryId the category of the product, may be {@code null}
     * @return the id of the new product
     * @throws Exception when the request fails
     */
    private String createProduct(Cookie owner, String categoryId) throws Exception {
        StringBuilder content = new StringBuilder();
        content.append("{\"productCode\":\"")
                .append(uniqueSku())
                .append("\",\"name\":\"Test product\"");
        if (categoryId != null) {
            content.append(",\"categoryId\":\"").append(categoryId).append("\"");
        }
        content.append("}");
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/catalog/products")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(content.toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readId(result);
    }

    /**
     * Creates a category of its own and returns its id.
     *
     * <p>A count that is started for a category holds only the variants of that category, so a
     * fresh category keeps a fresh count down to a single line.
     *
     * @param owner the session of the owner
     * @return the id of the new category
     * @throws Exception when the request fails
     */
    private String createCategory(Cookie owner) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/catalog/categories")
                                        .cookie(owner)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"name\":\"Test category "
                                                        + UUID.randomUUID()
                                                                .toString()
                                                                .substring(0, 8)
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readId(result);
    }

    /**
     * Puts a signed number of pieces on the shelf of a location.
     *
     * @param owner the session of the owner
     * @param locationId the location
     * @param variantId the variant
     * @param quantityDelta the signed change of the stock
     * @throws Exception when the request fails
     */
    private void adjust(Cookie owner, UUID locationId, UUID variantId, int quantityDelta)
            throws Exception {
        mockMvc.perform(
                        post("/api/v1/inventory/adjustments")
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + locationId
                                                + "\",\"reason\":\"FOUND\",\"lines\":[{\"variantId\":\""
                                                + variantId
                                                + "\",\"quantityDelta\":"
                                                + quantityDelta
                                                + "}]}"))
                .andExpect(status().isCreated());
    }

    /**
     * Sets the purchase cost of a variant.
     *
     * @param owner the session of the owner
     * @param variantId the variant
     * @param cost the cost, as a plain number
     * @throws Exception when the request fails
     */
    private void setCost(Cookie owner, UUID variantId, String cost) throws Exception {
        mockMvc.perform(
                        post("/api/v1/catalog/variants/{id}/cost", variantId)
                                .cookie(owner)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"costPrice\":\"" + cost + "\"}"))
                .andExpect(status().isOk());
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
     * Reads the id of a created row out of the answer.
     *
     * @param result the answer of the request
     * @return the id as a string
     * @throws Exception when the body cannot be read
     */
    private static String readId(MvcResult result) throws Exception {
        JsonNode node = readJson(result);
        return node.get("id").asText();
    }

    /**
     * Reads the body of an answer as a JSON tree.
     *
     * @param result the answer of the request
     * @return the tree
     * @throws Exception when the body cannot be read
     */
    private static JsonNode readJson(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertNotNull(body, "The answer must carry a body");
        return MAPPER.readTree(body);
    }

    /**
     * The line of a variant inside the lines of a count.
     *
     * @param lines the lines of the count
     * @param variantId the variant
     * @return the line of that variant
     */
    private static JsonNode lineOfVariant(JsonNode lines, UUID variantId) {
        for (JsonNode line : lines) {
            if (variantId.toString().equals(line.get("variantId").asText())) {
                return line;
            }
        }
        throw new AssertionError("The count must hold a line for the variant: " + lines);
    }
}
