package com.merqo.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.common.web.PageRequest;
import com.merqo.common.web.PageResponse;
import com.merqo.user.CurrentUserService;

/**
 * The endpoints of the stock: the balances, the adjustments, the policies and the counts.
 *
 * <p>Responsibility: translate HTTP into calls on {@link StockService}.
 *
 * <p>Every list is scoped by the permission of the caller, so a branch manager sees the stock of
 * their own branches only. The location in the query is a filter inside that scope, never a way out
 * of it.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Data transfer object</b> - the request bodies are small records.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Stock, adjustments, policies and counts")
public class InventoryController {

    private final StockService stockService;
    private final CurrentUserService currentUserService;

    /**
     * Creates the controller.
     *
     * @param stockService the stock rules
     * @param currentUserService the signed in user
     */
    public InventoryController(StockService stockService, CurrentUserService currentUserService) {
        this.stockService = stockService;
        this.currentUserService = currentUserService;
    }

    /**
     * The stock of the locations the caller may see.
     *
     * @param locationId the location filter
     * @param q the search text
     * @param lowStockOnly whether only the low stock rows are wanted
     * @param includeInactive whether the inactive variants are wanted too
     * @return the rows, one per location and variant
     */
    @GetMapping("/stock")
    @Operation(summary = "The stock of the locations the caller may see")
    public List<Map<String, Object>> listStock(
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean lowStockOnly,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        AccessContext context = currentUserService.requireAccessContext();
        return stockService.listStock(context, locationId, q, lowStockOnly, includeInactive);
    }

    /**
     * The totals of every location the caller may see.
     *
     * @return the totals, one per location
     */
    @GetMapping("/stock/totals")
    @Operation(summary = "The totals of every location the caller may see")
    public List<Map<String, Object>> locationTotals() {
        AccessContext context = currentUserService.requireAccessContext();
        return stockService.locationTotals(context);
    }

    /**
     * The movements of the locations the caller may see.
     *
     * @param locationId the location filter
     * @param variantId the variant filter
     * @param page the page number
     * @param size the page size
     * @return the page of movements
     */
    @GetMapping("/movements")
    @Operation(summary = "The movements of the locations the caller may see")
    public PageResponse<Map<String, Object>> listMovements(
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID variantId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        AccessContext context = currentUserService.requireAccessContext();
        PageRequest request = PageRequest.of(page, size, "id", PageRequest.ASC);
        // The order of the history is the one of the query: the creation moment and then the
        // sequence number. A sort on the pageable would be appended to it, so the page is unsorted.
        Page<StockMovement> result =
                stockService.listMovements(
                        context, locationId, variantId, request.toUnsortedSpring());
        List<Map<String, Object>> items = new ArrayList<>();
        result.forEach(movement -> items.add(toMovement(movement)));
        return PageResponse.of(
                new PageImpl<>(items, result.getPageable(), result.getTotalElements()));
    }

    /**
     * Creates a stock adjustment. All lines are applied or none of them is.
     *
     * @param request the adjustment
     * @return the created adjustment
     */
    @PostMapping("/adjustments")
    @Operation(summary = "Create a stock adjustment")
    public ResponseEntity<Map<String, Object>> createAdjustment(
            @Valid @RequestBody CreateAdjustmentRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        List<StockService.AdjustmentLine> lines = new ArrayList<>();
        for (AdjustmentLineRequest line : request.lines()) {
            lines.add(new StockService.AdjustmentLine(line.variantId(), line.quantityDelta()));
        }
        StockAdjustment adjustment =
                stockService.createAdjustment(
                        context, request.locationId(), request.reason(), request.note(), lines);
        return ResponseEntity.status(201).body(toAdjustment(adjustment));
    }

    /**
     * The adjustments of the locations the caller may see.
     *
     * @param locationId the location filter
     * @return the adjustments
     */
    @GetMapping("/adjustments")
    @Operation(summary = "The adjustments of the locations the caller may see")
    public List<Map<String, Object>> listAdjustments(
            @RequestParam(required = false) UUID locationId) {
        AccessContext context = currentUserService.requireAccessContext();
        List<Map<String, Object>> items = new ArrayList<>();
        for (StockAdjustment adjustment : stockService.listAdjustments(context, locationId)) {
            items.add(toAdjustment(adjustment));
        }
        return items;
    }

    /**
     * One adjustment with its lines.
     *
     * @param id the adjustment
     * @return the adjustment
     */
    @GetMapping("/adjustments/{id}")
    @Operation(summary = "One adjustment with its lines")
    public Map<String, Object> getAdjustment(@PathVariable UUID id) {
        currentUserService.requireAccessContext();
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (StockAdjustmentLine line : stockService.linesOf(id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("variantId", line.getVariantId());
            entry.put("quantityDelta", line.getQuantityDelta());
            entry.put("unitCost", line.getUnitCost());
            entry.put("valueAtCost", line.getValueAtCost());
            lines.add(entry);
        }
        body.put("lines", lines);
        return body;
    }

    /**
     * Changes the reorder point of a variant at a location.
     *
     * @param request the policy
     * @return the changed policy
     */
    @PutMapping("/policies")
    @Operation(summary = "Change the reorder point of a variant at a location")
    public Map<String, Object> savePolicy(@Valid @RequestBody SavePolicyRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        InventoryPolicy policy =
                stockService.savePolicy(
                        context,
                        request.locationId(),
                        request.variantId(),
                        request.reorderPoint(),
                        request.targetStock(),
                        request.onlineSafetyStock());
        return toPolicy(policy);
    }

    /**
     * The policy of a variant at a location.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the policy
     */
    @GetMapping("/policies")
    @Operation(summary = "The policy of a variant at a location")
    public Map<String, Object> getPolicy(
            @RequestParam UUID locationId, @RequestParam UUID variantId) {
        currentUserService.requireAccessContext();
        return stockService.policyOf(locationId, variantId).map(this::toPolicy).orElseGet(Map::of);
    }

    // ---- Counts -------------------------------------------------------------

    /**
     * The counts of the locations the caller may see.
     *
     * @param locationId the location filter
     * @return the counts
     */
    @GetMapping("/counts")
    @Operation(summary = "The counts of the locations the caller may see")
    public List<Map<String, Object>> listCounts(@RequestParam(required = false) UUID locationId) {
        AccessContext context = currentUserService.requireAccessContext();
        List<Map<String, Object>> items = new ArrayList<>();
        for (StockCount count : stockService.listCounts(context, locationId)) {
            items.add(toCount(count));
        }
        return items;
    }

    /**
     * Starts a count of one location.
     *
     * @param request the count to start
     * @return the new count
     */
    @PostMapping("/counts")
    @Operation(summary = "Start a count of one location")
    public ResponseEntity<Map<String, Object>> startCount(
            @Valid @RequestBody StartCountRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        StockCount count =
                stockService.startCount(context, request.locationId(), request.categoryId());
        return ResponseEntity.status(201).body(toCount(count));
    }

    /**
     * One count with its lines.
     *
     * @param id the count
     * @return the count
     */
    @GetMapping("/counts/{id}")
    @Operation(summary = "One count with its lines")
    public Map<String, Object> getCount(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        StockCount count = stockService.getCount(context, id);
        Map<String, Object> body = toCount(count);
        body.put("lines", stockService.countLines(context, count));
        return body;
    }

    /**
     * Writes the quantity the counter counted for one line.
     *
     * @param id the count
     * @param request the counted quantity
     * @return the changed count
     */
    @PutMapping("/counts/{id}/lines")
    @Operation(summary = "Write the counted quantity of one line")
    public Map<String, Object> countLine(
            @PathVariable UUID id, @Valid @RequestBody CountLineRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        StockCount count = stockService.getCount(context, id);
        stockService.countLine(context, count, request.variantId(), request.countedQuantity());
        return getCount(id);
    }

    /**
     * Submits a count. The variances become visible.
     *
     * @param id the count
     * @return the submitted count
     */
    @PostMapping("/counts/{id}/submit")
    @Operation(summary = "Submit a count")
    public Map<String, Object> submitCount(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        StockCount count = stockService.getCount(context, id);
        return toCount(stockService.submitCount(context, count));
    }

    /**
     * Applies a count. The variances change the stock.
     *
     * @param id the count
     * @return the applied count
     */
    @PostMapping("/counts/{id}/apply")
    @Operation(summary = "Apply a count")
    public Map<String, Object> applyCount(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        StockCount count = stockService.getCount(context, id);
        return toCount(stockService.applyCount(context, count));
    }

    /**
     * Cancels a count.
     *
     * @param id the count
     * @return the cancelled count
     */
    @PostMapping("/counts/{id}/cancel")
    @Operation(summary = "Cancel a count")
    public Map<String, Object> cancelCount(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        StockCount count = stockService.getCount(context, id);
        return toCount(stockService.cancelCount(context, count));
    }

    private Map<String, Object> toMovement(StockMovement movement) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", movement.getId());
        body.put("locationId", movement.getLocationId());
        body.put("variantId", movement.getVariantId());
        body.put("movementType", movement.getMovementType().name());
        body.put("quantityDelta", movement.getQuantityDelta());
        body.put("onHandAfter", movement.getOnHandAfter());
        body.put("referenceType", movement.getReferenceType());
        body.put("referenceId", movement.getReferenceId());
        body.put("reason", movement.getReason());
        body.put("note", movement.getNote());
        body.put("unitCost", movement.getUnitCost());
        body.put("createdBy", movement.getCreatedBy());
        body.put("createdAt", movement.getCreatedAt());
        return body;
    }

    private Map<String, Object> toAdjustment(StockAdjustment adjustment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", adjustment.getId());
        body.put("number", adjustment.getNumber());
        body.put("locationId", adjustment.getLocationId());
        body.put("reason", adjustment.getReason().name());
        body.put("note", adjustment.getNote());
        body.put("isFlagged", adjustment.isFlagged());
        body.put("totalValueAtCost", adjustment.getTotalValueAtCost());
        body.put("createdBy", adjustment.getCreatedBy());
        body.put("createdAt", adjustment.getCreatedAt());
        return body;
    }

    private Map<String, Object> toPolicy(InventoryPolicy policy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", policy.getId());
        body.put("locationId", policy.getLocationId());
        body.put("variantId", policy.getVariantId());
        body.put("reorderPoint", policy.getReorderPoint());
        body.put("targetStock", policy.getTargetStock());
        body.put("onlineSafetyStock", policy.getOnlineSafetyStock());
        return body;
    }

    private Map<String, Object> toCount(StockCount count) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", count.getId());
        body.put("number", count.getNumber());
        body.put("locationId", count.getLocationId());
        body.put("status", count.getStatus().name());
        body.put("categoryId", count.getCategoryId());
        body.put("isFlagged", count.isFlagged());
        body.put("createdBy", count.getCreatedBy());
        body.put("createdAt", count.getCreatedAt());
        body.put("submittedAt", count.getSubmittedAt());
        body.put("appliedAt", count.getAppliedAt());
        return body;
    }

    /**
     * The body of a create adjustment request.
     *
     * @param locationId the location
     * @param reason the reason
     * @param note the note, mandatory when the reason is OTHER
     * @param lines the lines
     */
    public record CreateAdjustmentRequest(
            @NotNull UUID locationId,
            @NotNull StockAdjustment.Reason reason,
            String note,
            @NotEmpty List<@Valid AdjustmentLineRequest> lines) {}

    /**
     * One line of an adjustment.
     *
     * @param variantId the variant
     * @param quantityDelta the signed change of the stock
     */
    public record AdjustmentLineRequest(@NotNull UUID variantId, int quantityDelta) {}

    /**
     * The body of a save policy request.
     *
     * @param locationId the location
     * @param variantId the variant
     * @param reorderPoint the reorder point
     * @param targetStock the target stock
     * @param onlineSafetyStock the online safety stock
     */
    public record SavePolicyRequest(
            @NotNull UUID locationId,
            @NotNull UUID variantId,
            @PositiveOrZero int reorderPoint,
            @PositiveOrZero int targetStock,
            @PositiveOrZero int onlineSafetyStock) {}

    /**
     * The body of a start count request.
     *
     * @param locationId the location
     * @param categoryId the category to count, may be {@code null} for the whole location
     */
    public record StartCountRequest(@NotNull UUID locationId, UUID categoryId) {}

    /**
     * The body of a count line request.
     *
     * @param variantId the variant
     * @param countedQuantity the quantity on the shelf
     */
    public record CountLineRequest(@NotNull UUID variantId, @PositiveOrZero int countedQuantity) {}
}
