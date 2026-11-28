package com.merqo.orders;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Order line item capturing SKU, product metadata, and catalog online price at order creation. */
@Entity
@Table(name = "order_lines")
public class OrderLine {

    @Id private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

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

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "catalog_online_price", precision = 14, scale = 2)
    private BigDecimal catalogOnlinePrice;

    protected OrderLine() {}

    public OrderLine(
            UUID id,
            UUID orderId,
            int lineIndex,
            UUID variantId,
            String productNameSnapshot,
            String skuSnapshot,
            String barcodeSnapshot,
            String colorSnapshot,
            String sizeSnapshot,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal catalogOnlinePrice) {
        this.id = id;
        this.orderId = orderId;
        this.lineIndex = lineIndex;
        this.variantId = variantId;
        this.productNameSnapshot = productNameSnapshot;
        this.skuSnapshot = skuSnapshot;
        this.barcodeSnapshot = barcodeSnapshot;
        this.colorSnapshot = colorSnapshot;
        this.sizeSnapshot = sizeSnapshot;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineTotal = lineTotal;
        this.catalogOnlinePrice = catalogOnlinePrice;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
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

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public BigDecimal getCatalogOnlinePrice() {
        return catalogOnlinePrice;
    }
}
