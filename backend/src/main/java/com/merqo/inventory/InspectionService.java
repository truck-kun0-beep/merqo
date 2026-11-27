package com.merqo.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.catalog.CatalogService;
import com.merqo.catalog.Product;
import com.merqo.catalog.ProductRepository;
import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.inventory.domain.AdjustmentFlagPolicy;
import com.merqo.inventory.domain.MovementType;
import com.merqo.location.LocationRepository;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;

/**
 * Application service for viewing and resolving goods held in inspection.
 *
 * <p>Responsibility: list scoped balances where {@code inInspection > 0} (without leaking cost to
 * callers lacking cost permission) and resolve inspected quantities back into sellable stock
 * ({@code RESTOCK}) or write them off ({@code WRITE_OFF}, value-flagged and audited).
 *
 * <p>OOP concept: <b>Facade &amp; Policy Delegation</b> — coordinates {@link InventoryService},
 * {@link AdjustmentFlagPolicy}, and {@link AuditService}.
 */
@Service
public class InspectionService {

    public enum ResolutionOutcome {
        RESTOCK,
        WRITE_OFF
    }

    private final StockBalanceRepository balanceRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final InventoryService inventoryService;
    private final CatalogService catalogService;
    private final SettingService settingService;
    private final AuditService auditService;

    public InspectionService(
            StockBalanceRepository balanceRepository,
            ProductVariantRepository variantRepository,
            ProductRepository productRepository,
            LocationRepository locationRepository,
            InventoryService inventoryService,
            CatalogService catalogService,
            SettingService settingService,
            AuditService auditService) {
        this.balanceRepository = balanceRepository;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.locationRepository = locationRepository;
        this.inventoryService = inventoryService;
        this.catalogService = catalogService;
        this.settingService = settingService;
        this.auditService = auditService;
    }

    /** Lists all variants currently in inspection within the caller's visible locations. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listInspections(AccessContext context, UUID locationId) {
        Set<UUID> visible =
                context.isOwner()
                        ? new LinkedHashSet<>(locationRepository.findAllIds())
                        : context.visibleLocationIds(Permission.INVENTORY_VIEW);
        if (locationId != null && !visible.contains(locationId)) {
            throw new NotFoundException("The requested record was not found.");
        }
        Set<UUID> wanted = locationId == null ? visible : Set.of(locationId);
        if (wanted.isEmpty()) {
            return List.of();
        }

        List<StockBalance> balances =
                balanceRepository.findByLocationIdInOrderByLocationIdAscVariantIdAsc(wanted);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StockBalance balance : balances) {
            if (balance.getInInspection() <= 0) {
                continue;
            }
            ProductVariant variant =
                    variantRepository.findById(balance.getVariantId()).orElse(null);
            Product product =
                    variant == null
                            ? null
                            : productRepository.findById(variant.getProductId()).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("locationId", balance.getLocationId());
            row.put("variantId", balance.getVariantId());
            row.put("sku", variant == null ? null : variant.getSku());
            row.put("productName", product == null ? null : product.getName());
            row.put("color", variant == null ? null : variant.getColor());
            row.put("size", variant == null ? null : variant.getSize());
            row.put("inInspection", balance.getInInspection());
            row.put("onHand", balance.getOnHand());
            row.put("available", balance.getAvailable());
            rows.add(row);
        }
        return rows;
    }

    /** Resolves inspected units either back to sellable stock or as a write-off. */
    @Transactional
    public Map<String, Object> resolve(
            AccessContext context,
            UUID locationId,
            UUID variantId,
            int quantity,
            ResolutionOutcome outcome,
            String note) {
        if (locationId == null || variantId == null || outcome == null) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "locationId, variantId, and outcome are required.");
        }
        context.requireInScope(locationId);
        context.require(Permission.INVENTORY_ADJUST, locationId);

        if (quantity <= 0) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "Quantity must be positive.");
        }
        if (!variantRepository.existsById(variantId)) {
            throw new NotFoundException("The requested record was not found.");
        }

        StockBalance existing = inventoryService.balanceOf(locationId, variantId);
        if (existing == null || existing.getInInspection() < quantity) {
            throw new ConflictException(
                    ErrorCodes.INSUFFICIENT_STOCK,
                    "Insufficient stock in inspection at this location.");
        }

        BigDecimal unitCost = catalogService.currentCost(variantId);
        BigDecimal valueAtCost =
                unitCost == null
                        ? BigDecimal.ZERO
                        : unitCost.multiply(BigDecimal.valueOf(quantity))
                                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal threshold =
                settingService.get(SettingKey.THRESHOLD_UNUSUAL_ADJUSTMENT_VALUE, BigDecimal.class);
        boolean isFlagged =
                outcome == ResolutionOutcome.WRITE_OFF
                        && AdjustmentFlagPolicy.isUnusual(valueAtCost, threshold);

        int accepted = outcome == ResolutionOutcome.RESTOCK ? quantity : 0;
        MovementType movementType =
                outcome == ResolutionOutcome.RESTOCK
                        ? MovementType.INSPECTION_RESTOCK
                        : MovementType.INSPECTION_WRITE_OFF;

        String trimmedNote = note == null || note.trim().isEmpty() ? null : note.trim();
        int onHandAfter =
                inventoryService.resolveInspection(
                        new InventoryCommand(
                                locationId,
                                variantId,
                                quantity,
                                movementType,
                                "INSPECTION_RESOLUTION",
                                null,
                                outcome.name(),
                                trimmedNote,
                                unitCost,
                                context.getUserId(),
                                null),
                        accepted);

        StockBalance updated = inventoryService.balanceOf(locationId, variantId);

        Map<String, Object> auditValues = new LinkedHashMap<>();
        auditValues.put("variantId", variantId.toString());
        auditValues.put("outcome", outcome.name());
        auditValues.put("quantity", quantity);
        auditValues.put("accepted", accepted);
        auditValues.put("valueAtCost", valueAtCost.toPlainString());
        auditValues.put("isFlagged", isFlagged);
        if (trimmedNote != null) {
            auditValues.put("note", trimmedNote);
        }
        auditService.record(
                new AuditEvent(
                        AuditAction.INSPECTION_RESOLVED,
                        "INSPECTION",
                        variantId,
                        locationId,
                        context.getUserId(),
                        context.getUsername(),
                        null,
                        auditValues,
                        null,
                        null));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("locationId", locationId);
        result.put("variantId", variantId);
        result.put("outcome", outcome.name());
        result.put("quantity", quantity);
        result.put("onHand", onHandAfter);
        result.put("inInspection", updated == null ? 0 : updated.getInInspection());
        result.put("isFlagged", isFlagged);
        if (context.can(Permission.COST_VIEW, locationId)
                || context.can(Permission.PRICE_EDIT, locationId)) {
            result.put("valueAtCost", valueAtCost.toPlainString());
        }
        return result;
    }
}
