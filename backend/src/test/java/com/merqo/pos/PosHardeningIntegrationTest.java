package com.merqo.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import com.merqo.access.PermissionService;
import com.merqo.access.PermissionTemplate;
import com.merqo.access.PermissionTemplateRepository;
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
 * Slice G hardening tests: append-only database triggers on all 6 Phase 3 ledger tables, Jackson
 * cost-privacy checks, full audit trail checks, 4 POS concurrency races (5th in
 * CashSessionIntegrationTest), and the stock balance/movement invariant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PosHardeningIntegrationTest {

    private static final String SESSION_COOKIE = "MERQO_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FORBIDDEN_COST_KEYS =
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

    @Autowired private MockMvc mockMvc;
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
    @DisplayName("append-only triggers block UPDATE and DELETE on all six Phase 3 ledger tables")
    void appendOnlyTriggersOnSixTables() throws Exception {
        UUID dmId = locationId("DM");
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);
        ProductVariant variant = createVariant(ownerCtx, "HRD-AO-" + shortId(), "500.00", "250.00");
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

        Cookie mgr =
                createAndSignIn(
                        "hrd.ao." + shortId(), "Pass#1234", "MANAGER", dmId, new BigDecimal("50"));
        openSession(mgr, dmId, "500.00");

        String saleJson =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(mgr)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "locationId": "%s",
                                                  "lines": [{"variantId": "%s", "quantity": 1}],
                                                  "payment": {"method": "CASH", "tendered": "500.00"}
                                                }
                                                """
                                                        .formatted(dmId, variant.getId())))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode saleNode = MAPPER.readTree(saleJson);
        UUID saleId = UUID.fromString(saleNode.get("id").asText());
        UUID saleLineId = UUID.fromString(saleNode.get("lines").get(0).get("id").asText());

        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/reprint")
                                .cookie(mgr)
                                .with(CsrfTestSupport.csrf()))
                .andExpect(status().isOk());

        String retJson =
                mockMvc.perform(
                                post("/api/v1/pos/sales/" + saleId + "/returns")
                                        .cookie(mgr)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "reason": "CHANGED_MIND",
                                                  "lines": [{"saleLineId": "%s", "quantity": 1, "disposition": "RESTOCK"}]
                                                }
                                                """
                                                        .formatted(saleLineId)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID returnId = UUID.fromString(MAPPER.readTree(retJson).get("id").asText());

        // Verify triggers block UPDATE & DELETE on all 6 tables
        assertThrows(
                Exception.class,
                () -> jdbcTemplate.update("UPDATE sales SET note = 'x' WHERE id = ?", saleId));
        assertThrows(
                Exception.class,
                () -> jdbcTemplate.update("DELETE FROM sales WHERE id = ?", saleId));

        assertThrows(
                Exception.class,
                () ->
                        jdbcTemplate.update(
                                "UPDATE sale_lines SET quantity = 99 WHERE id = ?", saleLineId));
        assertThrows(
                Exception.class,
                () -> jdbcTemplate.update("DELETE FROM sale_lines WHERE id = ?", saleLineId));

        assertThrows(
                Exception.class,
                () ->
                        jdbcTemplate.update(
                                "UPDATE sale_payments SET amount = 0 WHERE sale_id = ?", saleId));
        assertThrows(
                Exception.class,
                () -> jdbcTemplate.update("DELETE FROM sale_payments WHERE sale_id = ?", saleId));

        assertThrows(
                Exception.class,
                () ->
                        jdbcTemplate.update(
                                "UPDATE sale_returns SET note = 'x' WHERE id = ?", returnId));
        assertThrows(
                Exception.class,
                () -> jdbcTemplate.update("DELETE FROM sale_returns WHERE id = ?", returnId));

        assertThrows(
                Exception.class,
                () ->
                        jdbcTemplate.update(
                                "UPDATE sale_return_lines SET quantity = 99 WHERE sale_return_id = ?",
                                returnId));
        assertThrows(
                Exception.class,
                () ->
                        jdbcTemplate.update(
                                "DELETE FROM sale_return_lines WHERE sale_return_id = ?",
                                returnId));

        assertThrows(
                Exception.class,
                () ->
                        jdbcTemplate.update(
                                "UPDATE receipt_reprints SET printed_at = now() WHERE sale_id = ?",
                                saleId));
        assertThrows(
                Exception.class,
                () ->
                        jdbcTemplate.update(
                                "DELETE FROM receipt_reprints WHERE sale_id = ?", saleId));
    }

    @Test
    @DisplayName("Jackson cost privacy check and audit trail verification across all POS actions")
    void costPrivacyAndAuditRows() throws Exception {
        UUID dmId = locationId("DM");
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);

        String barcode = "55" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Product product =
                catalogService.createProduct(
                        ownerCtx,
                        "HRD-CP-" + shortId(),
                        "Cost Privacy Shirt",
                        null,
                        null,
                        null,
                        null);
        ProductVariant variant =
                catalogService.createVariant(
                        ownerCtx,
                        product,
                        "HRD-CP-SKU-" + shortId(),
                        "White",
                        "M",
                        null,
                        new BigDecimal("500.00"),
                        barcode);
        catalogService.changeCost(
                ownerCtx,
                variant,
                new BigDecimal("480.00"),
                VariantCostHistory.SourceType.MANUAL,
                null);
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

        String approverName = "hrd.mgr." + shortId();
        Cookie approver =
                createAndSignIn(approverName, "Pass#1234", "MANAGER", dmId, new BigDecimal("50"));
        mockMvc.perform(
                        put("/api/v1/auth/approval-pin")
                                .cookie(approver)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentPassword\":\"Pass#1234\",\"pin\":\"7492\"}"))
                .andExpect(status().isNoContent());

        String cashierName = "hrd.csh." + shortId();
        Cookie cashier =
                createAndSignIn(cashierName, "Pass#1234", "CASHIER", dmId, new BigDecimal("5"));
        UUID sessionId = openSession(cashier, dmId, "1000.00");

        // Trigger one failed approval
        mockMvc.perform(
                        post("/api/v1/pos/approvals")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"locationId\":\""
                                                + dmId
                                                + "\",\"pin\":\"0000\",\"maxDiscountPercent\":10}"))
                .andExpect(status().isUnprocessableContent());

        // Obtain approval for 10% (selling at 450.00, which is below cost 480.00!)
        String tokenJson =
                mockMvc.perform(
                                post("/api/v1/pos/approvals")
                                        .cookie(cashier)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"locationId\":\""
                                                        + dmId
                                                        + "\",\"pin\":\"7492\",\"maxDiscountPercent\":10}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String token = MAPPER.readTree(tokenJson).get("approvalToken").asText();

        String saleBody =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(cashier)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "locationId": "%s",
                                                  "lines": [{"variantId": "%s", "quantity": 1, "manualDiscountType": "PERCENT", "manualDiscountValue": "10"}],
                                                  "approvalToken": "%s",
                                                  "payment": {"method": "CASH", "tendered": "500.00"}
                                                }
                                                """
                                                        .formatted(dmId, variant.getId(), token)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode saleNode = MAPPER.readTree(saleBody);
        UUID saleId = UUID.fromString(saleNode.get("id").asText());
        String receiptNumber = saleNode.get("receiptNumber").asText();

        String receiptBody =
                mockMvc.perform(get("/api/v1/pos/sales/" + saleId + "/receipt").cookie(cashier))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String detailBody =
                mockMvc.perform(get("/api/v1/pos/sales/" + saleId).cookie(cashier))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String historyBody =
                mockMvc.perform(get("/api/v1/pos/sales").cookie(cashier))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String lookupSaleBody =
                mockMvc.perform(
                                get("/api/v1/pos/sales/lookup")
                                        .param("receiptNumber", receiptNumber)
                                        .cookie(cashier))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String catalogLookupBody =
                mockMvc.perform(
                                get("/api/v1/catalog/lookup")
                                        .param("barcode", barcode)
                                        .param("locationId", dmId.toString())
                                        .cookie(cashier))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Recursive Jackson key absence checks for cashier (never JSONPath != null!)
        assertNoForbiddenCostKeys(MAPPER.readTree(saleBody), "POST /pos/sales");
        assertNoForbiddenCostKeys(MAPPER.readTree(receiptBody), "GET /receipt");
        assertNoForbiddenCostKeys(MAPPER.readTree(detailBody), "GET /pos/sales/{id}");
        assertNoForbiddenCostKeys(MAPPER.readTree(historyBody), "GET /pos/sales");
        assertNoForbiddenCostKeys(MAPPER.readTree(lookupSaleBody), "GET /pos/sales/lookup");
        // Note: catalog lookup includes "belowCost": false when cost is hidden; assert cost keys
        // absent!
        JsonNode catNode = MAPPER.readTree(catalogLookupBody);
        assertFalse(catNode.has("cost"));
        assertFalse(catNode.has("costPrice"));
        assertFalse(catNode.get("variant").has("costPrice"));

        // Manager with COST_VIEW sees unitCost and belowCostWarnings on GET /pos/sales/{id}
        Cookie managerDm = signInDemo("manager-dm");
        JsonNode mgrSale =
                MAPPER.readTree(
                        mockMvc.perform(get("/api/v1/pos/sales/" + saleId).cookie(managerDm))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertTrue(mgrSale.has("belowCostWarnings"));
        assertTrue(mgrSale.get("lines").get(0).has("unitCost"));

        // Close session with variance to verify CASH_SESSION_CLOSED audit
        mockMvc.perform(
                        post("/api/v1/pos/sessions/" + sessionId + "/close")
                                .cookie(cashier)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"countedCash\":\"1400.00\",\"note\":\"Short 50\"}"))
                .andExpect(status().isOk());

        // Verify audit rows for approvals, failed approvals, below-cost sale, PIN change, session
        // close variance
        AppUser cashierUser = userRepository.findByUsernameIgnoreCase(cashierName).orElseThrow();
        AppUser approverUser = userRepository.findByUsernameIgnoreCase(approverName).orElseThrow();

        assertEquals(1, countAudits("APPROVAL_PIN_CHANGED", approverUser.getId().toString()));
        assertTrue(
                jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM audit_logs WHERE action = 'POS_APPROVAL_FAILED' AND actor_user_id = ?",
                                Integer.class,
                                cashierUser.getId())
                        >= 1);
        assertTrue(
                jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM audit_logs WHERE action = 'POS_DISCOUNT_APPROVED' AND actor_user_id = ?",
                                Integer.class,
                                cashierUser.getId())
                        >= 1);
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE action = 'POS_SALE_COMPLETED' AND entity_id = ? AND new_values->>'belowCost' = 'true'",
                        Integer.class,
                        saleId.toString()));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE action = 'CASH_SESSION_CLOSED' AND entity_id = ? AND new_values->>'hasVariance' = 'true'",
                        Integer.class,
                        sessionId.toString()));
    }

    @Test
    @DisplayName(
            "concurrency races: last unit, idempotent double-submit, last returnable unit, duplicate TrxID")
    void concurrencyRaces() throws Exception {
        UUID dmId = locationId("DM");
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);

        // 1. Two cashiers race on the last unit (stock = 1)
        ProductVariant lastUnit =
                createVariant(ownerCtx, "HRD-RACE1-" + shortId(), "100.00", "50.00");
        inventoryService.receive(
                new InventoryCommand(
                        dmId,
                        lastUnit.getId(),
                        1,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));

        Cookie c1 =
                createAndSignIn(
                        "hrd.r1a." + shortId(), "Pass#1234", "CASHIER", dmId, new BigDecimal("5"));
        Cookie c2 =
                createAndSignIn(
                        "hrd.r1b." + shortId(), "Pass#1234", "CASHIER", dmId, new BigDecimal("5"));
        openSession(c1, dmId, "100.00");
        openSession(c2, dmId, "100.00");

        List<Integer> lastUnitStatuses =
                runConcurrently(
                        () ->
                                postSaleStatus(
                                        c1, dmId, lastUnit.getId(), null, "CASH", "100.00", null),
                        () ->
                                postSaleStatus(
                                        c2, dmId, lastUnit.getId(), null, "CASH", "100.00", null));
        assertEquals(List.of(201, 409), lastUnitStatuses.stream().sorted().toList());
        assertEquals(0, inventoryService.balanceOf(dmId, lastUnit.getId()).getOnHand());

        // 2. One cashier double-submitting the same Idempotency-Key creates only 1 sale and drops
        // stock by 1
        ProductVariant idemVar =
                createVariant(ownerCtx, "HRD-IDEM-" + shortId(), "200.00", "100.00");
        inventoryService.receive(
                new InventoryCommand(
                        dmId,
                        idemVar.getId(),
                        5,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));
        String idemKey = "race-idem-" + shortId();
        List<String> saleIds =
                runConcurrently(
                        () ->
                                postSaleId(
                                        c1, dmId, idemVar.getId(), idemKey, "CASH", "200.00", null),
                        () ->
                                postSaleId(
                                        c1,
                                        dmId,
                                        idemVar.getId(),
                                        idemKey,
                                        "CASH",
                                        "200.00",
                                        null));
        assertEquals(saleIds.get(0), saleIds.get(1));
        assertEquals(4, inventoryService.balanceOf(dmId, idemVar.getId()).getOnHand());

        // 3. Two returns racing for the last returnable unit (sold = 1)
        Cookie mgr =
                createAndSignIn(
                        "hrd.retm." + shortId(),
                        "Pass#1234",
                        "MANAGER",
                        dmId,
                        new BigDecimal("50"));
        openSession(mgr, dmId, "100.00");
        String soldJson =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(mgr)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "locationId": "%s",
                                                  "lines": [{"variantId": "%s", "quantity": 1}],
                                                  "payment": {"method": "CASH", "tendered": "200.00"}
                                                }
                                                """
                                                        .formatted(dmId, idemVar.getId())))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode soldNode = MAPPER.readTree(soldJson);
        UUID raceSaleId = UUID.fromString(soldNode.get("id").asText());
        UUID raceLineId = UUID.fromString(soldNode.get("lines").get(0).get("id").asText());

        List<Integer> retStatuses =
                runConcurrently(
                        () -> postReturnStatus(mgr, raceSaleId, raceLineId),
                        () -> postReturnStatus(mgr, raceSaleId, raceLineId));
        assertEquals(List.of(201, 409), retStatuses.stream().sorted().toList());
        Integer returnedTotal =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(quantity), 0) FROM sale_return_lines WHERE sale_line_id = ?",
                        Integer.class,
                        raceLineId);
        assertEquals(1, returnedTotal);

        // 4. Two sales racing with the exact same bKash TrxID yield one 201 and one 409
        // DUPLICATE_REFERENCE
        String raceTrx = "RACETRX" + shortId().toUpperCase();
        List<Integer> trxStatuses =
                runConcurrently(
                        () ->
                                postSaleStatus(
                                        c1, dmId, idemVar.getId(), null, "BKASH", null, raceTrx),
                        () ->
                                postSaleStatus(
                                        c2, dmId, idemVar.getId(), null, "BKASH", null, raceTrx));
        assertEquals(List.of(201, 409), trxStatuses.stream().sorted().toList());
        Integer trxCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sale_payments WHERE method = 'BKASH' AND reference = ?",
                        Integer.class,
                        raceTrx);
        assertEquals(1, trxCount);
    }

    /**
     * Verifies the stock balance / movement invariant on a fresh variant after opening stock, POS
     * sale, RESTOCK return, INSPECTION return, and inspection resolutions (RESTOCK + WRITE_OFF).
     *
     * <p>Exact invariant formulas:
     *
     * <ul>
     *   <li>{@code SUM(stock_movements.quantity_delta) == stock_balances.on_hand}
     *   <li>{@code stock_balances.in_inspection == (INSPECTION return units) - (INSPECTION_RESTOCK
     *       + INSPECTION_WRITE_OFF units)}
     *   <li>{@code stock_balances.reserved <= stock_balances.on_hand} and all buckets {@code >= 0}
     * </ul>
     */
    @Test
    @DisplayName("stock invariant holds across sales, restock/inspection returns, and resolutions")
    void stockMovementAndBalanceInvariant() throws Exception {
        UUID dmId = locationId("DM");
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        AccessContext ownerCtx = permissionService.buildContext(owner);

        ProductVariant fresh = createVariant(ownerCtx, "HRD-INV-" + shortId(), "300.00", "150.00");
        inventoryService.receive(
                new InventoryCommand(
                        dmId,
                        fresh.getId(),
                        10,
                        MovementType.OPENING_BALANCE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        owner.getId(),
                        null));

        Cookie ownerCookie = signInDemo("owner");
        String mgrName = "hrd.invm." + shortId();
        Cookie mgr = createAndSignIn(mgrName, "Pass#1234", "MANAGER", dmId, new BigDecimal("50"));
        openSession(mgr, dmId, "500.00");

        // Sale of 4 -> on_hand 6
        String saleJson =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(mgr)
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "locationId": "%s",
                                                  "lines": [{"variantId": "%s", "quantity": 4}],
                                                  "payment": {"method": "CASH", "tendered": "1200.00"}
                                                }
                                                """
                                                        .formatted(dmId, fresh.getId())))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode saleNode = MAPPER.readTree(saleJson);
        UUID saleId = UUID.fromString(saleNode.get("id").asText());
        UUID lineId = UUID.fromString(saleNode.get("lines").get(0).get("id").asText());

        // Return 1 RESTOCK -> on_hand 7
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(mgr)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "WRONG_SIZE",
                                          "lines": [{"saleLineId": "%s", "quantity": 1, "disposition": "RESTOCK"}]
                                        }
                                        """
                                                .formatted(lineId)))
                .andExpect(status().isCreated());

        // Return 2 INSPECTION -> on_hand 7, in_inspection 2
        mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(mgr)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "DEFECTIVE",
                                          "lines": [{"saleLineId": "%s", "quantity": 2, "disposition": "INSPECTION"}]
                                        }
                                        """
                                                .formatted(lineId)))
                .andExpect(status().isCreated());

        // Resolve 1 RESTOCK -> on_hand 8, in_inspection 1
        mockMvc.perform(
                        post("/api/v1/inventory/inspections/resolve")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"locationId":"%s","variantId":"%s","quantity":1,"outcome":"RESTOCK"}
                                        """
                                                .formatted(dmId, fresh.getId())))
                .andExpect(status().isOk());

        // Resolve 1 WRITE_OFF -> on_hand 8, in_inspection 0
        mockMvc.perform(
                        post("/api/v1/inventory/inspections/resolve")
                                .cookie(ownerCookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"locationId":"%s","variantId":"%s","quantity":1,"outcome":"WRITE_OFF"}
                                        """
                                                .formatted(dmId, fresh.getId())))
                .andExpect(status().isOk());

        StockBalance balance = inventoryService.balanceOf(dmId, fresh.getId());
        Integer movementDeltaSum =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(quantity_delta), 0) FROM stock_movements WHERE location_id = ? AND variant_id = ?",
                        Integer.class,
                        dmId,
                        fresh.getId());

        assertEquals(8, balance.getOnHand());
        assertEquals(0, balance.getInInspection());
        assertEquals(movementDeltaSum, balance.getOnHand());
        assertTrue(balance.getReserved() <= balance.getOnHand());
        assertTrue(
                balance.getOnHand() >= 0
                        && balance.getReserved() >= 0
                        && balance.getInInspection() >= 0);
    }

    private void assertNoForbiddenCostKeys(JsonNode node, String contextLabel) {
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

    private Integer countAudits(String action, String entityId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Integer.class,
                action,
                entityId);
    }

    private int postSaleStatus(
            Cookie cookie,
            UUID locationId,
            UUID variantId,
            String idempotencyKey,
            String method,
            String tendered,
            String reference)
            throws Exception {
        var req =
                post("/api/v1/pos/sales")
                        .cookie(cookie)
                        .with(CsrfTestSupport.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildSaleJson(locationId, variantId, method, tendered, reference));
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(req).andReturn().getResponse().getStatus();
    }

    private String postSaleId(
            Cookie cookie,
            UUID locationId,
            UUID variantId,
            String idempotencyKey,
            String method,
            String tendered,
            String reference)
            throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/pos/sales")
                                        .cookie(cookie)
                                        .with(CsrfTestSupport.csrf())
                                        .header("Idempotency-Key", idempotencyKey)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                buildSaleJson(
                                                        locationId,
                                                        variantId,
                                                        method,
                                                        tendered,
                                                        reference)))
                        .andReturn();
        int status = result.getResponse().getStatus();
        assertTrue(status == 200 || status == 201, "Expected 200/201 but got " + status);
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private int postReturnStatus(Cookie cookie, UUID saleId, UUID saleLineId) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/pos/sales/" + saleId + "/returns")
                                .cookie(cookie)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "reason": "CHANGED_MIND",
                                          "lines": [{"saleLineId": "%s", "quantity": 1, "disposition": "RESTOCK"}]
                                        }
                                        """
                                                .formatted(saleLineId)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String buildSaleJson(
            UUID locationId, UUID variantId, String method, String tendered, String reference) {
        String tenderedPart = tendered == null ? "null" : "\"" + tendered + "\"";
        String refPart = reference == null ? "null" : "\"" + reference + "\"";
        return """
               {
                 "locationId": "%s",
                 "lines": [{"variantId": "%s", "quantity": 1}],
                 "payment": {"method": "%s", "tendered": %s, "reference": %s}
               }
               """
                .formatted(locationId, variantId, method, tenderedPart, refPart);
    }

    private <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<T> f1 =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return first.call();
                            });
            Future<T> f2 =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return second.call();
                            });
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            results.add(f1.get());
            results.add(f2.get());
            return results;
        } finally {
            pool.shutdown();
        }
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
                        "Green",
                        "L",
                        null,
                        new BigDecimal(price),
                        "44" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
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
                        username,
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
