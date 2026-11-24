package com.merqo.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * How much of a variant a location should keep.
 *
 * <p>Responsibility: hold the reorder point, the target stock and the online safety stock of one
 * variant at one location.
 *
 * <p>The reorder point is what makes the low stock flag work. A variant without a policy row uses
 * the default of the settings, and the rule lives in {@link
 * com.merqo.inventory.domain.LowStockPolicy}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Encapsulation</b> - the three numbers are only changed through the service, which also
 *       writes the audit row.
 * </ul>
 */
@Entity
@Table(name = "inventory_policies")
public class InventoryPolicy {

    @Id private UUID id;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "reorder_point", nullable = false)
    private int reorderPoint;

    @Column(name = "target_stock", nullable = false)
    private int targetStock;

    @Column(name = "online_safety_stock", nullable = false)
    private int onlineSafetyStock;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected InventoryPolicy() {}

    /**
     * Creates a policy.
     *
     * @param id the id
     * @param locationId the location
     * @param variantId the variant
     * @param reorderPoint the reorder point
     * @param targetStock the target stock
     * @param onlineSafetyStock the online safety stock
     * @param createdAt when it was created
     */
    public InventoryPolicy(
            UUID id,
            UUID locationId,
            UUID variantId,
            int reorderPoint,
            int targetStock,
            int onlineSafetyStock,
            Instant createdAt) {
        this.id = id;
        this.locationId = locationId;
        this.variantId = variantId;
        this.reorderPoint = reorderPoint;
        this.targetStock = targetStock;
        this.onlineSafetyStock = onlineSafetyStock;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
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
     * The location of the policy.
     *
     * @return the location id
     */
    public UUID getLocationId() {
        return locationId;
    }

    /**
     * The variant of the policy.
     *
     * @return the variant id
     */
    public UUID getVariantId() {
        return variantId;
    }

    /**
     * The number at which the variant is low on stock.
     *
     * @return the reorder point
     */
    public int getReorderPoint() {
        return reorderPoint;
    }

    /**
     * The number the location would like to keep.
     *
     * @return the target stock
     */
    public int getTargetStock() {
        return targetStock;
    }

    /**
     * The number that is kept back for the online orders.
     *
     * @return the online safety stock
     */
    public int getOnlineSafetyStock() {
        return onlineSafetyStock;
    }

    /**
     * Replaces the three numbers of the policy.
     *
     * @param reorderPoint the new reorder point
     * @param targetStock the new target stock
     * @param onlineSafetyStock the new online safety stock
     * @param updatedAt when the change happened
     */
    public void apply(int reorderPoint, int targetStock, int onlineSafetyStock, Instant updatedAt) {
        this.reorderPoint = reorderPoint;
        this.targetStock = targetStock;
        this.onlineSafetyStock = onlineSafetyStock;
        this.updatedAt = updatedAt;
    }

    /**
     * When the row was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the row was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
