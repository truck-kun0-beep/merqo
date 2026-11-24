package com.merqo.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.catalog.Product;
import com.merqo.catalog.ProductRepository;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.catalog.VariantCostHistory;
import com.merqo.catalog.VariantCostHistoryRepository;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.inventory.domain.AdjustmentFlagPolicy;
import com.merqo.inventory.domain.LowStockPolicy;
import com.merqo.inventory.domain.MovementType;
import com.merqo.inventory.domain.StockCountCalculator;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;

/**
 * The rules of the stock: the adjustments, the policies, the counts and the readings.
 *
 * <p>Responsibility: answer "how much stock is there", "change the stock", "count the stock" and
 * "how much should be kept", always inside the scope of the caller.
 *
 * <p>The rules that matter:
 *
 * <ul>
 *   <li>An adjustment is a whole document. Every line is applied or none of them is.
 *   <li>An adjustment that is worth more at cost than the threshold is flagged. The flag is written
 *       and the Action Center of Phase 8 reads it later. There is no approval workflow.
 *   <li>A count is blind while it is in progress. The counter never sees the quantity the system
 *       had.
 *   <li>The stock of a location is only visible to a caller who holds INVENTORY_VIEW there, and the
 *       value at cost is only visible to a caller who holds COST_VIEW or PRICE_EDIT there.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the stock rules live here, not in the controller.
 *   <li><b>Dependency injection</b> - the repositories, the settings and the clock are injected.
 *   <li><b>Facade</b> - the callers ask a question in business words and never touch a balance.
 *   <li><b>Fail fast</b> - every rule that is broken throws, so a broken request writes nothing.
 * </ul>
 */
@Service
public class StockService {

    private final StockBalanceRepository balanceRepository;
    private final StockMovementRepository movementRepository;
    private final InventoryPolicyRepository policyRepository;
    private final StockAdjustmentRepository adjustmentRepository;
    private final StockAdjustmentLineRepository adjustmentLineRepository;
    private final StockCountRepository countRepository;
    private final StockCountLineRepository countLineRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final VariantCostHistoryRepository costHistoryRepository;
    private final LocationRepository locationRepository;
    private final InventoryService inventoryService;
    private final SettingService settingService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param balanceRepository the balances
     * @param movementRepository the movements
     * @param policyRepository the policies
     * @param adjustmentRepository the adjustments
     * @param adjustmentLineRepository the lines of the adjustments
     * @param countRepository the counts
     * @param countLineRepository the lines of the counts
     * @param variantRepository the variants
     * @param productRepository the products
     * @param costHistoryRepository the cost history
     * @param locationRepository the locations
     * @param inventoryService the one place where the stock changes
     * @param settingService the settings
     * @param auditService the audit log
     * @param jdbcTemplate the tool that reads the number sequences
     * @param clock the clock
     */
    public StockService(
            StockBalanceRepository balanceRepository,
            StockMovementRepository movementRepository,
            InventoryPolicyRepository policyRepository,
            StockAdjustmentRepository adjustmentRepository,
            StockAdjustmentLineRepository adjustmentLineRepository,
            StockCountRepository countRepository,
            StockCountLineRepository countLineRepository,
            ProductVariantRepository variantRepository,
            ProductRepository productRepository,
            VariantCostHistoryRepository costHistoryRepository,
            LocationRepository locationRepository,
            InventoryService inventoryService,
            SettingService settingService,
            AuditService auditService,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.balanceRepository = balanceRepository;
        this.movementRepository = movementRepository;
        this.policyRepository = policyRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.adjustmentLineRepository = adjustmentLineRepository;
        this.countRepository = countRepository;
        this.countLineRepository = countLineRepository;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.costHistoryRepository = costHistoryRepository;
        this.locationRepository = locationRepository;
        this.inventoryService = inventoryService;
        this.settingService = settingService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    // ---- Readings -----------------------------------------------------------

    /**
     * The locations the caller may see for one permission.
     *
     * <p>The owner holds every permission everywhere, so the owner sees every location of the
     * business. Everybody else sees the locations where the permission is effective.
     *
     * @param context the caller
     * @param permission the permission
     * @return the location ids, never {@code null}
     */
    private Set<UUID> visibleLocations(AccessContext context, Permission permission) {
        if (context.isOwner()) {
            return new LinkedHashSet<>(locationRepository.findAllIds());
        }
        return context.visibleLocationIds(permission);
    }

    /**
     * The stock of the locations the caller may see.
     *
     * @param context the caller
     * @param locationId the location filter, may be {@code null}
     * @param search the search text, may be {@code null}
     * @param lowStockOnly whether only the low stock rows are wanted
     * @param includeInactive whether the inactive variants are wanted too
     * @return the rows, one per location and variant
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listStock(
            AccessContext context,
            UUID locationId,
            String search,
            boolean lowStockOnly,
            boolean includeInactive) {

        Set<UUID> visible = visibleLocations(context, Permission.INVENTORY_VIEW);
        if (locationId != null && !visible.contains(locationId)) {
            // The location is outside the scope of the caller. The answer is a 404, so the caller
            // cannot even learn that the location exists.
            throw new NotFoundException("The requested record was not found.");
        }
        Set<UUID> wanted = locationId == null ? visible : Set.of(locationId);
        if (wanted.isEmpty()) {
            return List.of();
        }

        List<StockBalance> balances =
                balanceRepository.findByLocationIdInOrderByLocationIdAscVariantIdAsc(wanted);
        Map<UUID, Integer> reorderPoints = reorderPoints(wanted);
        Map<UUID, BigDecimal> costs = costsOf(balances);
        Map<UUID, String> productNames = productNamesOf(balances);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (StockBalance balance : balances) {
            ProductVariant variant =
                    variantRepository.findById(balance.getVariantId()).orElse(null);
            if (variant == null) {
                continue;
            }
            if (!includeInactive && !variant.isActive()) {
                continue;
            }
            if (search != null
                    && !search.isBlank()
                    && !matches(variant, productNames.get(balance.getVariantId()), search)) {
                continue;
            }
            Integer reorderPoint = reorderPoints.get(balance.getVariantId());
            boolean lowStock =
                    LowStockPolicy.isLowStock(
                            balance.getAvailable(), reorderPoint, defaultReorderPoint());
            if (lowStockOnly && !lowStock) {
                continue;
            }
            rows.add(
                    toRow(
                            balance,
                            variant,
                            productNames.get(balance.getVariantId()),
                            reorderPoint,
                            lowStock,
                            context,
                            costs));
        }
        return rows;
    }

    /**
     * The totals of every location the caller may see.
     *
     * <p>The value at cost is only part of the answer when the caller may see the costs.
     *
     * @param context the caller
     * @return the totals, one per location
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> locationTotals(AccessContext context) {
        Set<UUID> visible = visibleLocations(context, Permission.INVENTORY_VIEW);
        if (visible.isEmpty()) {
            return List.of();
        }
        List<StockBalance> balances =
                balanceRepository.findByLocationIdInOrderByLocationIdAscVariantIdAsc(visible);
        Map<UUID, BigDecimal> costs = costsOf(balances);

        Map<UUID, Long> units = new LinkedHashMap<>();
        Map<UUID, BigDecimal> value = new LinkedHashMap<>();
        for (StockBalance balance : balances) {
            units.merge(balance.getLocationId(), (long) balance.getOnHand(), Long::sum);
            BigDecimal cost = costs.get(balance.getVariantId());
            if (cost != null) {
                value.merge(
                        balance.getLocationId(),
                        cost.multiply(BigDecimal.valueOf(balance.getOnHand())),
                        BigDecimal::add);
            }
        }

        List<Map<String, Object>> totals = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : units.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("locationId", entry.getKey());
            row.put("units", entry.getValue());
            if (maySeeCost(context, entry.getKey())) {
                row.put(
                        "valueAtCost",
                        value.getOrDefault(entry.getKey(), BigDecimal.ZERO)
                                .setScale(2, RoundingMode.HALF_UP));
            }
            totals.add(row);
        }
        return totals;
    }

    /**
     * The movements of the locations the caller may see.
     *
     * @param context the caller
     * @param locationId the location filter, may be {@code null}
     * @param pageable the paging
     * @return the page of movements
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<StockMovement> listMovements(
            AccessContext context,
            UUID locationId,
            UUID variantId,
            org.springframework.data.domain.Pageable pageable) {
        Set<UUID> visible = visibleLocations(context, Permission.INVENTORY_VIEW);
        if (locationId != null && !visible.contains(locationId)) {
            // The location is outside the scope of the caller, so the answer is a 404.
            throw new NotFoundException("The requested record was not found.");
        }
        List<UUID> wanted =
                locationId == null ? new ArrayList<>(visible) : new ArrayList<>(Set.of(locationId));
        if (wanted.isEmpty()) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        return movementRepository.search(wanted, variantId, pageable);
    }

    // ---- Adjustments --------------------------------------------------------

    /**
     * Changes the stock of a location with a whole document.
     *
     * @param context the caller
     * @param locationId the location
     * @param reason the reason
     * @param note the note, mandatory when the reason is OTHER
     * @param lines the lines, each with a variant and a signed quantity
     * @return the created adjustment
     */
    @Transactional
    public StockAdjustment createAdjustment(
            AccessContext context,
            UUID locationId,
            StockAdjustment.Reason reason,
            String note,
            List<AdjustmentLine> lines) {

        context.require(Permission.INVENTORY_ADJUST, locationId);
        Location location =
                locationRepository
                        .findById(locationId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!location.isActive()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "An inactive location has no stock to change.");
        }
        if (reason == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "The adjustment needs a reason.");
        }
        String trimmedNote = note == null ? "" : note.trim();
        if (reason == StockAdjustment.Reason.OTHER && trimmedNote.isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "The reason OTHER needs a note that says what happened.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "An adjustment needs at least one line.");
        }

        Instant now = Instant.now(clock);
        BigDecimal totalValueAtCost = BigDecimal.ZERO;

        // The lines are sorted by the variant, so two adjustments of the same variants always lock
        // the rows in the same order and never deadlock.
        List<AdjustmentLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing(AdjustmentLine::variantId));

        for (AdjustmentLine line : sorted) {
            if (!variantRepository.existsById(line.variantId())) {
                throw new NotFoundException("The requested record was not found.");
            }
            if (line.quantityDelta() == 0) {
                throw new ValidationException(
                        ErrorCodes.VALIDATION_FAILED, "A line may not change nothing.");
            }
            BigDecimal unitCost = currentCost(line.variantId());
            BigDecimal valueAtCost =
                    unitCost == null
                            ? BigDecimal.ZERO
                            : unitCost.multiply(BigDecimal.valueOf(Math.abs(line.quantityDelta())));
            totalValueAtCost = totalValueAtCost.add(valueAtCost);

            MovementType movementType =
                    line.quantityDelta() > 0
                            ? MovementType.ADJUSTMENT_IN
                            : MovementType.ADJUSTMENT_OUT;
            inventoryService.apply(
                    new InventoryCommand(
                            locationId,
                            line.variantId(),
                            line.quantityDelta(),
                            movementType,
                            "STOCK_ADJUSTMENT",
                            null,
                            reason.name(),
                            trimmedNote.isEmpty() ? null : trimmedNote,
                            unitCost,
                            context.getUserId(),
                            null));
        }

        String number = nextAdjustmentNumber();
        StockAdjustment adjustment =
                new StockAdjustment(
                        UUID.randomUUID(),
                        number,
                        locationId,
                        reason,
                        trimmedNote.isEmpty() ? null : trimmedNote,
                        totalValueAtCost.setScale(2, RoundingMode.HALF_UP),
                        context.getUserId(),
                        now);
        adjustment.setFlagged(
                AdjustmentFlagPolicy.isUnusual(
                        adjustment.getTotalValueAtCost(), unusualAdjustmentThreshold()));
        adjustmentRepository.save(adjustment);

        for (AdjustmentLine line : sorted) {
            BigDecimal unitCost = currentCost(line.variantId());
            BigDecimal valueAtCost =
                    unitCost == null
                            ? BigDecimal.ZERO
                            : unitCost.multiply(BigDecimal.valueOf(Math.abs(line.quantityDelta())));
            adjustmentLineRepository.save(
                    new StockAdjustmentLine(
                            UUID.randomUUID(),
                            adjustment.getId(),
                            line.variantId(),
                            line.quantityDelta(),
                            unitCost,
                            valueAtCost.setScale(2, RoundingMode.HALF_UP)));
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.ADJUSTMENT_CREATED, "stock_adjustments")
                        .entityId(adjustment.getId())
                        .locationId(locationId)
                        .actor(context.getUserId(), context.getUsername())
                        .added("number", number)
                        .added("reason", reason.name())
                        .added("lineCount", lines.size())
                        .added("totalValueAtCost", adjustment.getTotalValueAtCost())
                        .added("isFlagged", adjustment.isFlagged())
                        .added("note", adjustment.getNote())
                        .build());
        return adjustment;
    }

    /**
     * The adjustments of the locations the caller may see.
     *
     * @param context the caller
     * @param locationId the location filter, may be {@code null}
     * @return the adjustments
     */
    @Transactional(readOnly = true)
    public List<StockAdjustment> listAdjustments(AccessContext context, UUID locationId) {
        Set<UUID> visible = visibleLocations(context, Permission.INVENTORY_VIEW);
        if (visible.isEmpty()) {
            return List.of();
        }
        if (locationId != null) {
            if (!visible.contains(locationId)) {
                throw new NotFoundException("The requested record was not found.");
            }
            return adjustmentRepository.findByLocationIdOrderByCreatedAtDesc(locationId);
        }
        return adjustmentRepository.findByLocationIdInOrderByCreatedAtDesc(
                new ArrayList<>(visible));
    }

    /**
     * The lines of an adjustment.
     *
     * @param adjustmentId the adjustment
     * @return the lines
     */
    @Transactional(readOnly = true)
    public List<StockAdjustmentLine> linesOf(UUID adjustmentId) {
        return adjustmentLineRepository.findByAdjustmentId(adjustmentId);
    }

    // ---- Policies -----------------------------------------------------------

    /**
     * Changes the reorder point of a variant at a location.
     *
     * @param context the caller
     * @param locationId the location
     * @param variantId the variant
     * @param reorderPoint the new reorder point
     * @param targetStock the new target stock
     * @param onlineSafetyStock the new online safety stock
     * @return the changed policy
     */
    @Transactional
    public InventoryPolicy savePolicy(
            AccessContext context,
            UUID locationId,
            UUID variantId,
            int reorderPoint,
            int targetStock,
            int onlineSafetyStock) {

        context.require(Permission.INVENTORY_ADJUST, locationId);
        if (reorderPoint < 0 || targetStock < 0 || onlineSafetyStock < 0) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "The stock policy may not be negative.");
        }
        if (!variantRepository.existsById(variantId)) {
            throw new NotFoundException("The requested record was not found.");
        }

        Instant now = Instant.now(clock);
        InventoryPolicy policy =
                policyRepository
                        .findByLocationIdAndVariantId(locationId, variantId)
                        .orElseGet(
                                () ->
                                        new InventoryPolicy(
                                                UUID.randomUUID(),
                                                locationId,
                                                variantId,
                                                reorderPoint,
                                                targetStock,
                                                onlineSafetyStock,
                                                now));
        int oldReorderPoint = policy.getReorderPoint();
        int oldTargetStock = policy.getTargetStock();
        int oldOnlineSafetyStock = policy.getOnlineSafetyStock();
        policy.apply(reorderPoint, targetStock, onlineSafetyStock, now);
        policyRepository.save(policy);

        auditService.record(
                new AuditEvent.Builder(AuditAction.INVENTORY_POLICY_CHANGED, "inventory_policies")
                        .entityId(policy.getId())
                        .locationId(locationId)
                        .actor(context.getUserId(), context.getUsername())
                        .changed("reorderPoint", oldReorderPoint, reorderPoint)
                        .changed("targetStock", oldTargetStock, targetStock)
                        .changed("onlineSafetyStock", oldOnlineSafetyStock, onlineSafetyStock)
                        .build());
        return policy;
    }

    /**
     * The policy of a variant at a location.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the policy, if there is one
     */
    @Transactional(readOnly = true)
    public Optional<InventoryPolicy> policyOf(UUID locationId, UUID variantId) {
        return policyRepository.findByLocationIdAndVariantId(locationId, variantId);
    }

    // ---- Counts -------------------------------------------------------------

    /**
     * Starts a count of one location.
     *
     * <p>The snapshot of the system quantities is taken at this moment. While the count is in
     * progress the counter never sees those numbers, so the count is blind.
     *
     * @param context the caller
     * @param locationId the location
     * @param categoryId the category to count, may be {@code null} for the whole location
     * @return the new count
     */
    @Transactional
    public StockCount startCount(AccessContext context, UUID locationId, UUID categoryId) {
        context.require(Permission.STOCK_COUNT, locationId);
        Location location =
                locationRepository
                        .findById(locationId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!location.isActive()) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "An inactive location has no stock to count.");
        }
        // A location may have more than one open count. The Phase 2 rules do not forbid it, and two
        // counters may count two categories of the same branch at the same time.

        Instant now = Instant.now(clock);
        StockCount count =
                new StockCount(
                        UUID.randomUUID(),
                        nextCountNumber(),
                        locationId,
                        categoryId,
                        context.getUserId(),
                        now);
        countRepository.save(count);

        List<StockBalance> balances =
                balanceRepository.findByLocationIdOrderByVariantIdAsc(locationId);
        for (StockBalance balance : balances) {
            if (categoryId != null && !isInCategory(balance.getVariantId(), categoryId)) {
                continue;
            }
            countLineRepository.save(
                    new StockCountLine(
                            UUID.randomUUID(),
                            count.getId(),
                            balance.getVariantId(),
                            balance.getOnHand(),
                            null,
                            now));
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.COUNT_STARTED, "stock_counts")
                        .entityId(count.getId())
                        .locationId(locationId)
                        .actor(context.getUserId(), context.getUsername())
                        .added("number", count.getNumber())
                        .added("categoryId", categoryId)
                        .build());
        return count;
    }

    /**
     * The lines of a count.
     *
     * <p>While the count is in progress the answer holds no system quantity, so the counter can
     * only see what is on the shelf.
     *
     * @param context the caller
     * @param count the count
     * @return the lines
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> countLines(AccessContext context, StockCount count) {
        boolean blind = count.getStatus() == StockCount.Status.IN_PROGRESS;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StockCountLine line : countLineRepository.findByCountId(count.getId())) {
            ProductVariant variant = variantRepository.findById(line.getVariantId()).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("variantId", line.getVariantId());
            row.put("sku", variant == null ? null : variant.getSku());
            row.put("countedQuantity", line.getCountedQuantity());
            if (!blind) {
                row.put("systemQuantityAtStart", line.getSystemQuantityAtStart());
                row.put(
                        "variance",
                        StockCountCalculator.variance(
                                line.getSystemQuantityAtStart(), line.getCountedQuantity()));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Writes the quantity the counter counted for one line.
     *
     * @param context the caller
     * @param count the count
     * @param variantId the variant
     * @param countedQuantity the quantity on the shelf
     * @return the changed line
     */
    @Transactional
    public StockCountLine countLine(
            AccessContext context, StockCount count, UUID variantId, int countedQuantity) {
        context.require(Permission.STOCK_COUNT, count.getLocationId());
        requireOpen(count);
        if (countedQuantity < 0) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "The counted quantity may not be negative.");
        }
        StockCountLine line =
                countLineRepository
                        .findByCountIdAndVariantId(count.getId(), variantId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        line.setCountedQuantity(countedQuantity);
        countLineRepository.save(line);
        return line;
    }

    /**
     * Submits a count, so the variances become visible.
     *
     * @param context the caller
     * @param count the count
     * @return the submitted count
     */
    @Transactional
    public StockCount submitCount(AccessContext context, StockCount count) {
        context.require(Permission.STOCK_COUNT, count.getLocationId());
        requireOpen(count);
        count.markSubmitted(context.getUserId(), Instant.now(clock));
        countRepository.save(count);

        auditService.record(
                new AuditEvent.Builder(AuditAction.COUNT_SUBMITTED, "stock_counts")
                        .entityId(count.getId())
                        .locationId(count.getLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("status", "IN_PROGRESS", "SUBMITTED")
                        .build());
        return count;
    }

    /**
     * Applies the variances of a count to the stock of the moment.
     *
     * <p>The correction of a line is the difference between the counted quantity and the quantity
     * the system had when the count started, and it is applied to the stock of the moment. A sale
     * that happened during the count therefore survives the correction.
     *
     * @param context the caller
     * @param count the count
     * @return the applied count
     */
    @Transactional
    public StockCount applyCount(AccessContext context, StockCount count) {
        context.require(Permission.INVENTORY_ADJUST, count.getLocationId());
        if (count.getStatus() != StockCount.Status.SUBMITTED) {
            throw new ConflictException(
                    ErrorCodes.COUNT_NOT_OPEN, "Only a submitted count may be applied.");
        }

        List<StockCountLine> lines = countLineRepository.findByCountId(count.getId());
        BigDecimal totalValueAtCost = BigDecimal.ZERO;

        // The lines are sorted by the variant, so the balances are always locked in the same order.
        List<StockCountLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing(StockCountLine::getVariantId));

        for (StockCountLine line : sorted) {
            if (line.getCountedQuantity() == null) {
                throw new ConflictException(
                        ErrorCodes.COUNT_NOT_OPEN,
                        "Every line of the count must be counted before the count is applied.");
            }
            StockBalance balance =
                    balanceRepository
                            .findByLocationIdAndVariantId(
                                    count.getLocationId(), line.getVariantId())
                            .orElse(null);
            int currentOnHand = balance == null ? 0 : balance.getOnHand();
            int reserved = balance == null ? 0 : balance.getReserved();
            if (!StockCountCalculator.mayApply(
                    line.getSystemQuantityAtStart(),
                    line.getCountedQuantity(),
                    currentOnHand,
                    reserved)) {
                throw new ConflictException(
                        ErrorCodes.INSUFFICIENT_STOCK,
                        "The correction of the SKU "
                                + skuOf(line.getVariantId())
                                + " would take the stock below the reserved quantity.");
            }
            int correction =
                    StockCountCalculator.correction(
                            line.getSystemQuantityAtStart(),
                            line.getCountedQuantity(),
                            currentOnHand);
            if (correction == 0) {
                continue;
            }
            BigDecimal unitCost = currentCost(line.getVariantId());
            totalValueAtCost =
                    totalValueAtCost.add(
                            unitCost == null
                                    ? BigDecimal.ZERO
                                    : unitCost.multiply(BigDecimal.valueOf(Math.abs(correction))));
            inventoryService.apply(
                    new InventoryCommand(
                            count.getLocationId(),
                            line.getVariantId(),
                            correction,
                            MovementType.COUNT_CORRECTION,
                            "STOCK_COUNT",
                            count.getId(),
                            null,
                            null,
                            unitCost,
                            context.getUserId(),
                            null));
        }

        count.markApplied(context.getUserId(), Instant.now(clock));
        count.setFlagged(
                AdjustmentFlagPolicy.isUnusual(totalValueAtCost, unusualAdjustmentThreshold()));
        countRepository.save(count);

        auditService.record(
                new AuditEvent.Builder(AuditAction.COUNT_APPLIED, "stock_counts")
                        .entityId(count.getId())
                        .locationId(count.getLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("status", "SUBMITTED", "APPLIED")
                        .added("valueAtCost", totalValueAtCost.setScale(2, RoundingMode.HALF_UP))
                        .added("isFlagged", count.isFlagged())
                        .build());
        return count;
    }

    /**
     * Calls off a count before it is applied.
     *
     * @param context the caller
     * @param count the count
     * @return the cancelled count
     */
    @Transactional
    public StockCount cancelCount(AccessContext context, StockCount count) {
        context.require(Permission.STOCK_COUNT, count.getLocationId());
        requireOpen(count);
        count.markCancelled();
        countRepository.save(count);

        auditService.record(
                new AuditEvent.Builder(AuditAction.COUNT_CANCELLED, "stock_counts")
                        .entityId(count.getId())
                        .locationId(count.getLocationId())
                        .actor(context.getUserId(), context.getUsername())
                        .changed("status", "IN_PROGRESS", "CANCELLED")
                        .build());
        return count;
    }

    /**
     * One count.
     *
     * @param context the caller
     * @param countId the count
     * @return the count
     */
    @Transactional(readOnly = true)
    public StockCount getCount(AccessContext context, UUID countId) {
        StockCount count =
                countRepository
                        .findById(countId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!visibleLocations(context, Permission.INVENTORY_VIEW).contains(count.getLocationId())
                && !visibleLocations(context, Permission.STOCK_COUNT)
                        .contains(count.getLocationId())) {
            throw new NotFoundException("The requested record was not found.");
        }
        return count;
    }

    /**
     * The counts of the locations the caller may see.
     *
     * @param context the caller
     * @param locationId the location filter, may be {@code null}
     * @return the counts
     */
    @Transactional(readOnly = true)
    public List<StockCount> listCounts(AccessContext context, UUID locationId) {
        Set<UUID> visible = visibleLocations(context, Permission.INVENTORY_VIEW);
        if (visible.isEmpty()) {
            return List.of();
        }
        if (locationId != null) {
            if (!visible.contains(locationId)) {
                throw new NotFoundException("The requested record was not found.");
            }
            return countRepository.findByLocationIdOrderByCreatedAtDesc(locationId);
        }
        return countRepository.findByLocationIdInOrderByCreatedAtDesc(new ArrayList<>(visible));
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * The next number of an adjustment.
     *
     * @return the number, for example {@code ADJ-000001}
     */
    private String nextAdjustmentNumber() {
        return String.format("ADJ-%06d", nextSequenceValue("adjustment_number_sequence"));
    }

    /**
     * The next number of a count.
     *
     * @return the number, for example {@code CNT-000001}
     */
    private String nextCountNumber() {
        return String.format("CNT-%06d", nextSequenceValue("count_number_sequence"));
    }

    /**
     * The next value of one of the number sequences.
     *
     * @param sequenceName the name of the sequence
     * @return the next value
     */
    private long nextSequenceValue(String sequenceName) {
        Long value =
                jdbcTemplate.queryForObject("select nextval('" + sequenceName + "')", Long.class);
        return value == null ? 1L : value;
    }

    private Set<UUID> intersect(Set<UUID> first, Set<UUID> second) {
        Set<UUID> result = new LinkedHashSet<>();
        for (UUID id : first) {
            if (second.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private Map<UUID, Integer> reorderPoints(Set<UUID> locationIds) {
        Map<UUID, Integer> points = new LinkedHashMap<>();
        for (Object[] row : policyRepository.findReorderPoints(new ArrayList<>(locationIds))) {
            points.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return points;
    }

    private Map<UUID, BigDecimal> costsOf(List<StockBalance> balances) {
        Map<UUID, BigDecimal> costs = new LinkedHashMap<>();
        for (StockBalance balance : balances) {
            BigDecimal cost = currentCost(balance.getVariantId());
            if (cost != null) {
                costs.put(balance.getVariantId(), cost);
            }
        }
        return costs;
    }

    private Map<UUID, String> productNamesOf(List<StockBalance> balances) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (StockBalance balance : balances) {
            variantRepository
                    .findById(balance.getVariantId())
                    .ifPresent(
                            variant -> {
                                Product product =
                                        productRepository
                                                .findById(variant.getProductId())
                                                .orElse(null);
                                if (product != null) {
                                    names.put(balance.getVariantId(), product.getName());
                                }
                            });
        }
        return names;
    }

    private BigDecimal currentCost(UUID variantId) {
        return costHistoryRepository
                .findLatest(variantId)
                .map(VariantCostHistory::getValue)
                .orElse(null);
    }

    private BigDecimal unusualAdjustmentThreshold() {
        return settingService.get(SettingKey.THRESHOLD_UNUSUAL_ADJUSTMENT_VALUE, BigDecimal.class);
    }

    /**
     * The reorder point that is used when a variant has no policy row.
     *
     * <p>It comes from the settings, so the owner can change it without a code change.
     *
     * @return the default reorder point
     */
    private int defaultReorderPoint() {
        return settingService.get(SettingKey.INVENTORY_DEFAULT_REORDER_POINT, Integer.class);
    }

    private boolean maySeeCost(AccessContext context, UUID locationId) {
        return context.can(Permission.COST_VIEW, locationId)
                || context.can(Permission.PRICE_EDIT, locationId);
    }

    private boolean matches(ProductVariant variant, String productName, String search) {
        String lowered = search.toLowerCase(Locale.ROOT);
        if (variant.getSku() != null
                && variant.getSku().toLowerCase(Locale.ROOT).contains(lowered)) {
            return true;
        }
        return productName != null && productName.toLowerCase(Locale.ROOT).contains(lowered);
    }

    private boolean isInCategory(UUID variantId, UUID categoryId) {
        ProductVariant variant = variantRepository.findById(variantId).orElse(null);
        if (variant == null) {
            return false;
        }
        Product product = productRepository.findById(variant.getProductId()).orElse(null);
        return product != null && categoryId.equals(product.getCategoryId());
    }

    private String skuOf(UUID variantId) {
        return variantRepository.findById(variantId).map(ProductVariant::getSku).orElse("unknown");
    }

    private void requireOpen(StockCount count) {
        if (count.getStatus() != StockCount.Status.IN_PROGRESS) {
            throw new ConflictException(
                    ErrorCodes.COUNT_NOT_OPEN, "This count is not open any more.");
        }
    }

    private Map<String, Object> toRow(
            StockBalance balance,
            ProductVariant variant,
            String productName,
            Integer reorderPoint,
            boolean lowStock,
            AccessContext context,
            Map<UUID, BigDecimal> costs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("locationId", balance.getLocationId());
        row.put("variantId", balance.getVariantId());
        row.put("sku", variant.getSku());
        row.put("productName", productName);
        row.put("color", variant.getColor());
        row.put("size", variant.getSize());
        row.put("isActive", variant.isActive());
        row.put("onHand", balance.getOnHand());
        row.put("reserved", balance.getReserved());
        row.put("available", balance.getAvailable());
        row.put("inInspection", balance.getInInspection());
        row.put("reorderPoint", reorderPoint == null ? defaultReorderPoint() : reorderPoint);
        row.put("isLowStock", lowStock);
        if (maySeeCost(context, balance.getLocationId())) {
            BigDecimal cost = costs.get(balance.getVariantId());
            row.put("unitCost", cost);
            if (cost != null) {
                row.put(
                        "valueAtCost",
                        cost.multiply(BigDecimal.valueOf(balance.getOnHand()))
                                .setScale(2, RoundingMode.HALF_UP));
            }
        }
        return row;
    }

    /**
     * One line of an adjustment.
     *
     * @param variantId the variant
     * @param quantityDelta the signed change of the stock
     */
    public record AdjustmentLine(UUID variantId, int quantityDelta) {}
}
