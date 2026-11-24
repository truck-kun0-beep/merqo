package com.merqo.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The stock of one variant at one location, as it is stored.
 *
 * <p>Responsibility: hold the three numbers of the balance in the database. The rules that keep
 * them correct live in the plain Java class {@link com.merqo.inventory.domain.InventoryBalance},
 * and this row is only the place they are kept.
 *
 * <p>The row is created on the first movement of a variant at a location, and it is never deleted.
 * A variant that was counted down to zero still has a row, so the history stays readable.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Optimistic locking</b> - the version column makes two writers fight it out, so the
 *       second one is told to try again instead of silently overwriting the first.
 *   <li><b>Composition</b> - the row holds the ids of the location and the variant instead of
 *       copying their data.
 * </ul>
 */
@Entity
@Table(name = "inventory_balances")
public class StockBalance {

    @Id private UUID id;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Column(name = "in_inspection", nullable = false)
    private int inInspection;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected StockBalance() {}

    /**
     * Creates an empty balance.
     *
     * @param id the id
     * @param locationId the location
     * @param variantId the variant
     * @param createdAt when it was created
     */
    public StockBalance(UUID id, UUID locationId, UUID variantId, Instant createdAt) {
        this.id = id;
        this.locationId = locationId;
        this.variantId = variantId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.onHand = 0;
        this.reserved = 0;
        this.inInspection = 0;
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
     * The location of the stock.
     *
     * @return the location id
     */
    public UUID getLocationId() {
        return locationId;
    }

    /**
     * The variant of the stock.
     *
     * @return the variant id
     */
    public UUID getVariantId() {
        return variantId;
    }

    /**
     * The stock on the shelf.
     *
     * @return the stock on the shelf
     */
    public int getOnHand() {
        return onHand;
    }

    /**
     * The stock that is promised to somebody.
     *
     * @return the reserved stock
     */
    public int getReserved() {
        return reserved;
    }

    /**
     * The goods that are in inspection.
     *
     * @return the goods in inspection
     */
    public int getInInspection() {
        return inInspection;
    }

    /**
     * The stock that may be sold.
     *
     * @return the stock on the shelf minus the reservation
     */
    public int getAvailable() {
        return onHand - reserved;
    }

    /**
     * Replaces the three numbers of the balance with the ones the rules produced.
     *
     * @param onHand the new stock on the shelf
     * @param reserved the new reservation
     * @param inInspection the new goods in inspection
     * @param updatedAt when the change happened
     */
    public void apply(int onHand, int reserved, int inInspection, Instant updatedAt) {
        this.onHand = onHand;
        this.reserved = reserved;
        this.inInspection = inInspection;
        this.updatedAt = updatedAt;
    }

    /**
     * The version of the row, used by the optimistic lock.
     *
     * @return the version
     */
    public long getVersion() {
        return version;
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
