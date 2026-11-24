package com.merqo.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.inventory.domain.MovementType;

/**
 * One movement of the stock. The table is append only.
 *
 * <p>Responsibility: hold one physical change of the stock of one variant at one location.
 *
 * <p>Every change of the stock on the shelf or in inspection writes exactly one row of this table
 * in the same transaction as the change itself. The sum of the deltas of a balance is always its
 * stock on the shelf, and that is what the invariant test proves.
 *
 * <p>The table is append only. A database trigger raises an error on any UPDATE and on any DELETE,
 * and the repository has no update and no delete method.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Enums with behaviour</b> - the kind of movement is an enum value that knows its own
 *       sign.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Encapsulation</b> - a movement is never rewritten. It is the truth about what happened.
 * </ul>
 */
@Entity
@Table(name = "stock_movements")
public class StockMovement {

    @Id private UUID id;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private MovementType movementType;

    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    @Column(name = "on_hand_after", nullable = false)
    private int onHandAfter;

    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reason", length = 40)
    private String reason;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "unit_cost", precision = 14, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Required by JPA. */
    protected StockMovement() {}

    /**
     * Creates a movement.
     *
     * @param id the id
     * @param locationId the location
     * @param variantId the variant
     * @param movementType the kind of movement
     * @param quantityDelta the signed change of the stock
     * @param onHandAfter the stock on the shelf after the change
     * @param referenceType what caused the movement, may be {@code null}
     * @param referenceId the id of that cause, may be {@code null}
     * @param reason the reason, may be {@code null}
     * @param note the note, may be {@code null}
     * @param unitCost the cost of one piece at that moment, may be {@code null}
     * @param createdBy who caused the movement
     * @param createdAt when it happened
     * @param correlationId the id of the request, may be {@code null}
     */
    public StockMovement(
            UUID id,
            UUID locationId,
            UUID variantId,
            MovementType movementType,
            int quantityDelta,
            int onHandAfter,
            String referenceType,
            UUID referenceId,
            String reason,
            String note,
            BigDecimal unitCost,
            UUID createdBy,
            Instant createdAt,
            String correlationId) {
        this.id = id;
        this.locationId = locationId;
        this.variantId = variantId;
        this.movementType = movementType;
        this.quantityDelta = quantityDelta;
        this.onHandAfter = onHandAfter;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.reason = reason;
        this.note = note;
        this.unitCost = unitCost;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.correlationId = correlationId;
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
     * The location of the movement.
     *
     * @return the location id
     */
    public UUID getLocationId() {
        return locationId;
    }

    /**
     * The variant of the movement.
     *
     * @return the variant id
     */
    public UUID getVariantId() {
        return variantId;
    }

    /**
     * The kind of movement.
     *
     * @return the movement type
     */
    public MovementType getMovementType() {
        return movementType;
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
     * The stock on the shelf after the movement.
     *
     * @return the stock after the movement
     */
    public int getOnHandAfter() {
        return onHandAfter;
    }

    /**
     * What caused the movement.
     *
     * @return the reference type, or {@code null}
     */
    public String getReferenceType() {
        return referenceType;
    }

    /**
     * The id of the cause.
     *
     * @return the reference id, or {@code null}
     */
    public UUID getReferenceId() {
        return referenceId;
    }

    /**
     * The reason of the movement.
     *
     * @return the reason, or {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * The note of the movement.
     *
     * @return the note, or {@code null}
     */
    public String getNote() {
        return note;
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
     * Who caused the movement.
     *
     * @return the user id, or {@code null}
     */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /**
     * When the movement happened.
     *
     * @return the moment of the movement
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * The id of the request that caused the movement.
     *
     * @return the correlation id, or {@code null}
     */
    public String getCorrelationId() {
        return correlationId;
    }
}
