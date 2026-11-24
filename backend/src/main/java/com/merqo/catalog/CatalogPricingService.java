package com.merqo.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.LinePricing;
import com.merqo.catalog.domain.PriceCalculator;
import com.merqo.catalog.domain.Scope;
import com.merqo.catalog.domain.TaxPolicy;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.common.money.Money;
import com.merqo.inventory.InventoryService;
import com.merqo.inventory.StockBalance;
import com.merqo.promotion.Promotion;
import com.merqo.promotion.PromotionService;
import com.merqo.settings.SettingKey;
import com.merqo.settings.SettingService;

/**
 * Works out what a variant costs the customer right now.
 *
 * <p>Responsibility: turn a variant, a channel and a location into the numbers of a priced line:
 * the list price, the discount of the promotion that applies, the net unit price, the tax and the
 * total. The arithmetic itself is done by the pure Java {@link PriceCalculator}; this service only
 * finds the inputs and assembles the answer.
 *
 * <p>Two rules that matter for the screens:
 *
 * <ol>
 *   <li><b>The cost is hidden.</b> The purchase cost, the margin and the value of the stock are
 *       only part of the answer when the caller holds {@code COST_VIEW} or {@code PRICE_EDIT}. For
 *       everybody else the fields are simply not there, not zero.
 *   <li><b>The stock is hidden.</b> The stock of a location is only part of the answer when the
 *       caller may view that location. A branch manager therefore never sees the stock of the
 *       warehouse.
 * </ol>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Facade</b> - the controller asks for "the price of this variant" and never sees the
 *       promotion service, the settings and the inventory service.
 *   <li><b>Composition</b> - the answer is assembled from smaller objects instead of being
 *       calculated in one long method.
 *   <li><b>Delegation</b> - the heavy lifting is done by the price calculator, the promotion
 *       service and the inventory service.
 * </ul>
 */
@Service
public class CatalogPricingService {

    private final CatalogService catalogService;
    private final PromotionService promotionService;
    private final SettingService settingService;
    private final InventoryService inventoryService;

    /**
     * Creates the service.
     *
     * @param catalogService the catalogue rules
     * @param promotionService the discount rules
     * @param settingService the business settings
     * @param inventoryService the stock rules
     */
    public CatalogPricingService(
            CatalogService catalogService,
            PromotionService promotionService,
            SettingService settingService,
            InventoryService inventoryService) {
        this.catalogService = catalogService;
        this.promotionService = promotionService;
        this.settingService = settingService;
        this.inventoryService = inventoryService;
    }

    /**
     * The variant that carries a scanned barcode, with its price.
     *
     * <p>This is the endpoint the scanner talks to. The match is exact and case sensitive, and an
     * inactive variant is never returned: a deactivated variant may not be scanned.
     *
     * @param context the caller
     * @param barcode the scanned code
     * @param channel the channel the customer buys in
     * @param locationId the location of the purchase, may be {@code null}
     * @return the variant with its price breakdown
     */
    @Transactional(readOnly = true)
    public Map<String, Object> lookup(
            AccessContext context, String barcode, Scope channel, UUID locationId) {
        context.requireGlobal(Permission.PRODUCT_VIEW);
        requireLocationInScope(context, locationId);

        ProductVariant variant = catalogService.findByBarcode(barcode);
        if (!variant.isActive()) {
            throw new NotFoundException(
                    ErrorCodes.BARCODE_NOT_FOUND, "That barcode belongs to a deactivated variant.");
        }
        Product product = catalogService.getProduct(variant.getProductId());
        if (!product.isActive()) {
            throw new NotFoundException(
                    ErrorCodes.BARCODE_NOT_FOUND, "That barcode belongs to a deactivated product.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variant", catalogService.toVariant(variant, catalogService.maySeeCost(context)));
        body.put("barcodes", barcodesOf(variant));
        body.putAll(priceOf(context, variant, channel, locationId, 1));
        return body;
    }

    /**
     * The price of a variant, so that the price can be checked in the browser.
     *
     * @param context the caller
     * @param variantId the variant
     * @param channel the channel the customer buys in
     * @param locationId the location of the purchase, may be {@code null}
     * @param quantity how many pieces
     * @return the price breakdown
     */
    @Transactional(readOnly = true)
    public Map<String, Object> pricePreview(
            AccessContext context, UUID variantId, Scope channel, UUID locationId, int quantity) {
        context.requireGlobal(Permission.PRODUCT_VIEW);
        requireLocationInScope(context, locationId);
        if (quantity < 1) {
            throw new ValidationException(
                    ErrorCodes.VALIDATION_FAILED, "The quantity must be at least one.");
        }

        ProductVariant variant = catalogService.getVariant(variantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variant", catalogService.toVariant(variant, catalogService.maySeeCost(context)));
        body.putAll(priceOf(context, variant, channel, locationId, quantity));
        return body;
    }

    /**
     * The price breakdown of one variant.
     *
     * @param context the caller
     * @param variant the variant
     * @param channel the channel the customer buys in
     * @param locationId the location of the purchase, may be {@code null}
     * @param quantity how many pieces
     * @return the breakdown
     */
    @Transactional(readOnly = true)
    public Map<String, Object> priceOf(
            AccessContext context,
            ProductVariant variant,
            Scope channel,
            UUID locationId,
            int quantity) {

        Money listPrice = Money.of(variant.getSellingPrice());
        Promotion promotion =
                promotionService.resolve(variant.getId(), channel, locationId).orElse(null);
        Money promotionDiscount = discountOf(promotion, listPrice);

        BigDecimal taxRate = taxRateOf(variant);
        boolean taxInclusive = settingService.get(SettingKey.TAX_INCLUSIVE, Boolean.class);

        boolean maySeeCost = catalogService.maySeeCost(context);
        Money costPrice = maySeeCost ? costOf(variant) : null;

        LinePricing pricing =
                PriceCalculator.calculate(
                        listPrice,
                        promotionDiscount,
                        null,
                        quantity,
                        taxRate,
                        taxInclusive,
                        costPrice);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listPrice", pricing.listPrice().amount());
        body.put("promotion", promotionSummary(promotion));
        body.put("promotionDiscount", pricing.promotionDiscount().amount());
        body.put("manualDiscount", pricing.manualDiscount().amount());
        body.put("netUnitPrice", pricing.netUnitPrice().amount());
        body.put("quantity", pricing.quantity());
        body.put("subtotal", pricing.lineSubtotal().amount());
        body.put("taxRatePercent", taxRate);
        body.put("taxInclusive", taxInclusive);
        body.put("tax", pricing.tax().amount());
        body.put("total", pricing.lineTotal().amount());
        body.put("belowCost", pricing.belowCost());
        if (maySeeCost) {
            body.put("cost", costPrice == null ? null : costPrice.amount());
        }
        body.put("stock", stockOf(context, variant, locationId));
        return body;
    }

    /**
     * The discount of the promotion, as an amount off the unit price.
     *
     * @param promotion the promotion, may be {@code null}
     * @param listPrice the price of one piece
     * @return the discount, or {@code null} when there is no promotion
     */
    private Money discountOf(Promotion promotion, Money listPrice) {
        if (promotion == null) {
            return null;
        }
        if (promotion.getDiscountType() == DiscountType.PERCENT) {
            return PriceCalculator.percentDiscount(listPrice, promotion.getValue());
        }
        return PriceCalculator.fixedDiscount(listPrice, Money.of(promotion.getValue()));
    }

    /**
     * The tax rate that applies to a variant: the rate of the product, else the global one.
     *
     * @param variant the variant
     * @return the rate, where 15 means fifteen percent
     */
    private BigDecimal taxRateOf(ProductVariant variant) {
        BigDecimal productRate =
                catalogService.getProduct(variant.getProductId()).getTaxRatePercent();
        BigDecimal globalRate = settingService.get(SettingKey.TAX_RATE_PERCENT, BigDecimal.class);
        return TaxPolicy.effectiveRate(productRate, globalRate);
    }

    /**
     * The purchase cost of a variant.
     *
     * @param variant the variant
     * @return the cost, or {@code null} when the variant never had one
     */
    private Money costOf(ProductVariant variant) {
        BigDecimal cost = catalogService.currentCost(variant.getId());
        return cost == null ? null : Money.of(cost);
    }

    /**
     * The stock of a variant at a location, but only when the caller may view that location.
     *
     * @param context the caller
     * @param variant the variant
     * @param locationId the location, may be {@code null}
     * @return the stock, or {@code null} when it may not be shown
     */
    private Map<String, Object> stockOf(
            AccessContext context, ProductVariant variant, UUID locationId) {
        if (locationId == null) {
            return null;
        }
        if (!context.can(Permission.INVENTORY_VIEW, locationId)) {
            return null;
        }
        StockBalance balance = inventoryService.balanceOf(locationId, variant.getId());
        if (balance == null) {
            return null;
        }
        Map<String, Object> stock = new LinkedHashMap<>();
        stock.put("locationId", locationId);
        stock.put("onHand", balance.getOnHand());
        stock.put("reserved", balance.getReserved());
        stock.put("available", balance.getAvailable());
        stock.put("inInspection", balance.getInInspection());
        return stock;
    }

    /**
     * The barcodes of a variant, so that the screen can show the alternates too.
     *
     * @param variant the variant
     * @return the barcodes
     */
    private List<Map<String, Object>> barcodesOf(ProductVariant variant) {
        List<Map<String, Object>> barcodes = new ArrayList<>();
        for (VariantBarcode barcode : catalogService.barcodesOf(variant.getId())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", barcode.getId());
            entry.put("barcode", barcode.getBarcode());
            entry.put("kind", barcode.getKind().name());
            barcodes.add(entry);
        }
        return barcodes;
    }

    /**
     * The promotion that applies, as the interface shows it.
     *
     * @param promotion the promotion, may be {@code null}
     * @return the summary, or {@code null} when no promotion applies
     */
    private Map<String, Object> promotionSummary(Promotion promotion) {
        if (promotion == null) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", promotion.getId());
        summary.put("name", promotion.getName());
        summary.put("discountType", promotion.getDiscountType().name());
        summary.put("value", promotion.getValue());
        summary.put("scope", promotion.getScope().name());
        return summary;
    }

    /**
     * Refuses a location that is outside the scope of the caller.
     *
     * @param context the caller
     * @param locationId the location, may be {@code null}
     */
    private void requireLocationInScope(AccessContext context, UUID locationId) {
        if (locationId != null) {
            context.requireInScope(locationId);
        }
    }
}
