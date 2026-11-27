package com.merqo.pos;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.catalog.domain.DiscountType;

/**
 * Append-only JPA entity representing a single line item of a completed POS sale.
 *
 * <p>Responsibility: persist immutable product/variant snapshots, resolved promotion and manual
 * discounts, tax breakdown, and internal cost snapshot. Database triggers forbid UPDATE and DELETE.
 *
 * <p>OOP concept: <b>Immutability &amp; Snapshot Pattern</b> — captures point-in-time prices,
 * names, and cost so future catalog edits never rewrite historical receipts.
 */
@Entity
@Table(name = "sale_lines")
public class SaleLine {

    @Id private UUID id;

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    @Column(name = "line_index", nullable = false)
    private int lineIndex;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "product_name_snapshot", nullable = false, length = 160)
    private String productNameSnapshot;

    @Column(name = "sku_snapshot", nullable = false, length = 60)
    private String skuSnapshot;

    @Column(name = "barcode_snapshot", length = 64)
    private String barcodeSnapshot;

    @Column(name = "color_snapshot", length = 60)
    private String colorSnapshot;

    @Column(name = "size_snapshot", length = 60)
    private String sizeSnapshot;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "list_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "promotion_id")
    private UUID promotionId;

    @Column(name = "promotion_name_snapshot", length = 160)
    private String promotionNameSnapshot;

    @Column(name = "promotion_discount", nullable = false, precision = 14, scale = 2)
    private BigDecimal promotionDiscount;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_discount_type", length = 20)
    private DiscountType manualDiscountType;

    @Column(name = "manual_discount_value", precision = 14, scale = 2)
    private BigDecimal manualDiscountValue;

    @Column(name = "manual_discount", nullable = false, precision = 14, scale = 2)
    private BigDecimal manualDiscount;

    @Column(name = "bill_discount_allocated", nullable = false, precision = 14, scale = 2)
    private BigDecimal billDiscountAllocated;

    @Column(name = "discount_applied_by")
    private UUID discountAppliedBy;

    @Column(name = "tax_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRatePercent;

    @Column(name = "tax_inclusive", nullable = false)
    private boolean taxInclusive;

    @Column(name = "tax_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "net_unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal netUnitPrice;

    @Column(name = "line_subtotal", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineSubtotal;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "unit_cost_snapshot", precision = 14, scale = 2)
    private BigDecimal unitCostSnapshot;

    @Column(name = "below_cost", nullable = false)
    private boolean belowCost;

    /** Required by JPA. */
    protected SaleLine() {}

    public SaleLine(
            UUID id,
            UUID saleId,
            int lineIndex,
            UUID variantId,
            String productNameSnapshot,
            String skuSnapshot,
            String barcodeSnapshot,
            String colorSnapshot,
            String sizeSnapshot,
            int quantity,
            BigDecimal listPrice,
            UUID promotionId,
            String promotionNameSnapshot,
            BigDecimal promotionDiscount,
            DiscountType manualDiscountType,
            BigDecimal manualDiscountValue,
            BigDecimal manualDiscount,
            BigDecimal billDiscountAllocated,
            UUID discountAppliedBy,
            BigDecimal taxRatePercent,
            boolean taxInclusive,
            BigDecimal taxAmount,
            BigDecimal netUnitPrice,
            BigDecimal lineSubtotal,
            BigDecimal lineTotal,
            BigDecimal unitCostSnapshot,
            boolean belowCost) {
        this.id = id;
        this.saleId = saleId;
        this.lineIndex = lineIndex;
        this.variantId = variantId;
        this.productNameSnapshot = productNameSnapshot;
        this.skuSnapshot = skuSnapshot;
        this.barcodeSnapshot = barcodeSnapshot;
        this.colorSnapshot = colorSnapshot;
        this.sizeSnapshot = sizeSnapshot;
        this.quantity = quantity;
        this.listPrice = listPrice;
        this.promotionId = promotionId;
        this.promotionNameSnapshot = promotionNameSnapshot;
        this.promotionDiscount = promotionDiscount;
        this.manualDiscountType = manualDiscountType;
        this.manualDiscountValue = manualDiscountValue;
        this.manualDiscount = manualDiscount;
        this.billDiscountAllocated = billDiscountAllocated;
        this.discountAppliedBy = discountAppliedBy;
        this.taxRatePercent = taxRatePercent;
        this.taxInclusive = taxInclusive;
        this.taxAmount = taxAmount;
        this.netUnitPrice = netUnitPrice;
        this.lineSubtotal = lineSubtotal;
        this.lineTotal = lineTotal;
        this.unitCostSnapshot = unitCostSnapshot;
        this.belowCost = belowCost;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public int getLineIndex() {
        return lineIndex;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public String getProductNameSnapshot() {
        return productNameSnapshot;
    }

    public String getSkuSnapshot() {
        return skuSnapshot;
    }

    public String getBarcodeSnapshot() {
        return barcodeSnapshot;
    }

    public String getColorSnapshot() {
        return colorSnapshot;
    }

    public String getSizeSnapshot() {
        return sizeSnapshot;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getListPrice() {
        return listPrice;
    }

    public UUID getPromotionId() {
        return promotionId;
    }

    public String getPromotionNameSnapshot() {
        return promotionNameSnapshot;
    }

    public BigDecimal getPromotionDiscount() {
        return promotionDiscount;
    }

    public DiscountType getManualDiscountType() {
        return manualDiscountType;
    }

    public BigDecimal getManualDiscountValue() {
        return manualDiscountValue;
    }

    public BigDecimal getManualDiscount() {
        return manualDiscount;
    }

    public BigDecimal getBillDiscountAllocated() {
        return billDiscountAllocated;
    }

    public UUID getDiscountAppliedBy() {
        return discountAppliedBy;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public boolean isTaxInclusive() {
        return taxInclusive;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getNetUnitPrice() {
        return netUnitPrice;
    }

    public BigDecimal getLineSubtotal() {
        return lineSubtotal;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public BigDecimal getUnitCostSnapshot() {
        return unitCostSnapshot;
    }

    public boolean isBelowCost() {
        return belowCost;
    }
}
