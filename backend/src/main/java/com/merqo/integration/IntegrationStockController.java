package com.merqo.integration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.ProductVariantRepository;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.inventory.InventoryPolicy;
import com.merqo.inventory.InventoryPolicyRepository;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.StockBalance;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.orders.domain.OnlineAvailabilityCalculator;

/** External website stock availability endpoint protected by HMAC signature auth. */
@RestController
@RequestMapping("/integration/v1")
@Tag(name = "Website Integration", description = "HMAC-authenticated endpoints for external stores")
public class IntegrationStockController {

    private final LocationRepository locationRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryService inventoryService;
    private final InventoryPolicyRepository policyRepository;
    private final SoftHoldReader softHoldReader;

    public IntegrationStockController(
            LocationRepository locationRepository,
            ProductVariantRepository variantRepository,
            InventoryService inventoryService,
            InventoryPolicyRepository policyRepository,
            ObjectProvider<SoftHoldReader> softHoldReaderProvider) {
        this.locationRepository = locationRepository;
        this.variantRepository = variantRepository;
        this.inventoryService = inventoryService;
        this.policyRepository = policyRepository;
        this.softHoldReader = softHoldReaderProvider.getIfAvailable(() -> (w, v) -> 0);
    }

    @GetMapping("/stock")
    @Operation(summary = "Query online sellable availability for one or more SKUs")
    public StockAvailabilityResponse stock(
            @RequestParam(name = "sku", required = false) List<String> sku) {
        Location warehouse =
                locationRepository
                        .findFirstByOnlineFulfillmentTrueAndActiveTrue()
                        .orElseThrow(
                                () ->
                                        new ConflictException(
                                                ErrorCodes.ONLINE_LOCATION_NOT_SET,
                                                "No active warehouse is configured for online"
                                                        + " fulfillment."));

        List<String> requestedSkus = normalizeSkus(sku);
        List<StockAvailabilityItem> items = new ArrayList<>();
        for (String requestedSku : requestedSkus) {
            ProductVariant variant =
                    variantRepository.findBySkuIgnoreCase(requestedSku).orElse(null);
            if (variant == null || !variant.isActive()) {
                items.add(new StockAvailabilityItem(requestedSku, false, false, 0));
                continue;
            }
            StockBalance balance = inventoryService.balanceOf(warehouse.getId(), variant.getId());
            int onHand = balance == null ? 0 : balance.getOnHand();
            int reserved = balance == null ? 0 : balance.getReserved();
            int safety =
                    policyRepository
                            .findByLocationIdAndVariantId(warehouse.getId(), variant.getId())
                            .map(InventoryPolicy::getOnlineSafetyStock)
                            .orElse(0);
            int softHolds = softHoldReader.softHoldForVariant(warehouse.getId(), variant.getId());
            int available =
                    OnlineAvailabilityCalculator.available(onHand, reserved, softHolds, safety);
            items.add(new StockAvailabilityItem(variant.getSku(), true, available > 0, available));
        }
        return new StockAvailabilityResponse(warehouse.getId(), items);
    }

    private static List<String> normalizeSkus(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> seenLower = new LinkedHashSet<>();
        List<String> ordered = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (!trimmed.isEmpty() && seenLower.add(trimmed.toLowerCase(Locale.ROOT))) {
                ordered.add(trimmed);
            }
        }
        return ordered;
    }

    public record StockAvailabilityResponse(
            java.util.UUID locationId, List<StockAvailabilityItem> items) {}

    public record StockAvailabilityItem(
            String sku, boolean found, boolean inStock, int available) {}
}
