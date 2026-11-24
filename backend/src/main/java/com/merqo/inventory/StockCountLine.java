package com.merqo.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One line of a stock count.
 *
 * <p>Responsibility: hold the quantity the system had when the count started and the quantity the
 * counter wrote down.
 *
 * <p>The snapshot is taken when the count starts, so the difference is measured against the
 * beginning of the count and not against the stock of the moment. That is what lets a sale that
 * happened during the count survive the correction.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Null Object</b> - a line that was not counted yet holds no counted quantity, and the
 *       service leaves it out of the variances.
 * </ul>
 */
@Entity
@Table(name = "stock_count_lines")
public class StockCountLine {

    @Id private UUID id;

    @Column(name = "count_id", nullable = false)
    private UUID countId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "system_qty_at_start", nullable = false)
    private int systemQuantityAtStart;

    @Column(name = "counted_qty")
    private Integer countedQuantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected StockCountLine() {}

    /**
     * Creates a line.
     *
     * @param id the id
     * @param countId the count
     * @param variantId the variant
     * @param systemQuantityAtStart the quantity the system had when the count started
     * @param countedQuantity the quantity the counter wrote down, may be {@code null}
     * @param createdAt when the line was created
     */
    public StockCountLine(
            UUID id,
            UUID countId,
            UUID variantId,
            int systemQuantityAtStart,
            Integer countedQuantity,
            Instant createdAt) {
        this.id = id;
        this.countId = countId;
        this.variantId = variantId;
        this.systemQuantityAtStart = systemQuantityAtStart;
        this.countedQuantity = countedQuantity;
        this.createdAt = createdAt;
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
     * The count of the line.
     *
     * @return the count id
     */
    public UUID getCountId() {
        return countId;
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
     * The quantity the system had when the count started.
     *
     * @return the snapshot
     */
    public int getSystemQuantityAtStart() {
        return systemQuantityAtStart;
    }

    /**
     * The quantity the counter wrote down.
     *
     * @return the counted quantity, or {@code null} when the line is not counted yet
     */
    public Integer getCountedQuantity() {
        return countedQuantity;
    }

    /**
     * Records the quantity the counter wrote down.
     *
     * @param countedQuantity the counted quantity
     */
    public void setCountedQuantity(Integer countedQuantity) {
        this.countedQuantity = countedQuantity;
    }

    /**
     * When the line was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
