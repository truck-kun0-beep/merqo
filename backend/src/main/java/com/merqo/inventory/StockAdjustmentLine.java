package com.merqo.inventory;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One line of a stock adjustment.
 *
 * <p>Responsibility: hold how much one variant changed and what that change was worth at cost.
 *
 * <p>The cost is stored on the line, because it is a snapshot. The cost of a variant may change
 * later, and the value of an old adjustment must not change with it.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Composition</b> - the line belongs to the adjustment and is never used on its own.
 * </ul>
 */
@Entity
@Table(name = "stock_adjustment_lines")
public class StockAdjustmentLine {

    @Id private UUID id;

    @Column(name = "adjustment_id", nullable = false)
    private UUID adjustmentId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    @Column(name = "unit_cost", precision = 14, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "value_at_cost", nullable = false, precision = 14, scale = 2)
    private BigDecimal valueAtCost;

    /** Required by JPA. */
    protected StockAdjustmentLine() {}

    /**
     * Creates a line.
     *
     * @param id the id
     * @param adjustmentId the adjustment
     * @param variantId the variant
     * @param quantityDelta the signed change of the stock
     * @param unitCost the cost of one piece, may be {@code null}
     * @param valueAtCost the value of the change at cost
     */
    public StockAdjustmentLine(
            UUID id,
            UUID adjustmentId,
            UUID variantId,
            int quantityDelta,
            BigDecimal unitCost,
            BigDecimal valueAtCost) {
        this.id = id;
        this.adjustmentId = adjustmentId;
        this.variantId = variantId;
        this.quantityDelta = quantityDelta;
        this.unitCost = unitCost;
        this.valueAtCost = valueAtCost;
    }

    /**
     * The id of the row.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The adjustment of the line.
     *
     * @return the adjustment id
     */
    public UUID getAdjustmentId() {
        return adjustmentId;
    }

    /**
     * The variant of the line.
     *
     * @return the variant id
     */
    public UUID getVariantId() {
        return variantId;
    }

    /**
     * The signed change of the stock.
     *
     * @return the delta
     */
    public int getQuantityDelta() {
        return quantityDelta;
    }

    /**
     * The cost of one piece at that moment.
     *
     * @return the unit cost, or {@code null}
     */
    public BigDecimal getUnitCost() {
        return unitCost;
    }

    /**
     * The value of the change at cost.
     *
     * @return the value at cost
     */
    public BigDecimal getValueAtCost() {
        return valueAtCost;
    }
}
