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

/**
 * One document that changes the stock of a location.
 *
 * <p>Responsibility: hold the head of an adjustment: the number of the document, the location, the
 * reason and whether the value at cost was unusual.
 *
 * <p>An adjustment is a whole document. Every line is applied or none of them is, because the lines
 * live in their own table and are written in the same transaction as the head.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Enums with behaviour</b> - the reason is an enum value with its own rules.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Encapsulation</b> - a document is never deleted, so the history of the stock stays
 *       complete.
 * </ul>
 */
@Entity
@Table(name = "stock_adjustments")
public class StockAdjustment {

    /** The reasons an adjustment may have. */
    public enum Reason {
        /** The goods were damaged. */
        DAMAGED,
        /** The goods were lost. */
        LOST,
        /** The goods were found. */
        FOUND,
        /** The records were wrong. */
        DATA_CORRECTION,
        /** Something else, and a note is then mandatory. */
        OTHER
    }

    @Id private UUID id;

    @Column(name = "number", nullable = false, length = 20)
    private String number;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private Reason reason;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "is_flagged", nullable = false)
    private boolean flagged;

    @Column(name = "total_value_at_cost", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalValueAtCost;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected StockAdjustment() {}

    /**
     * Creates an adjustment.
     *
     * @param id the id
     * @param number the human readable number
     * @param locationId the location
     * @param reason the reason
     * @param note the note, may be {@code null}
     * @param totalValueAtCost the value of the change at cost
     * @param createdBy who created it
     * @param createdAt when it was created
     */
    public StockAdjustment(
            UUID id,
            String number,
            UUID locationId,
            Reason reason,
            String note,
            BigDecimal totalValueAtCost,
            UUID createdBy,
            Instant createdAt) {
        this.id = id;
        this.number = number;
        this.locationId = locationId;
        this.reason = reason;
        this.note = note;
        this.totalValueAtCost = totalValueAtCost;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.flagged = false;
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
     * The human readable number of the document.
     *
     * @return the number
     */
    public String getNumber() {
        return number;
    }

    /**
     * The location of the adjustment.
     *
     * @return the location id
     */
    public UUID getLocationId() {
        return locationId;
    }

    /**
     * The reason of the adjustment.
     *
     * @return the reason
     */
    public Reason getReason() {
        return reason;
    }

    /**
     * The note of the adjustment.
     *
     * @return the note, or {@code null}
     */
    public String getNote() {
        return note;
    }

    /**
     * Whether the value at cost was above the threshold.
     *
     * @return {@code true} when the adjustment is unusual
     */
    public boolean isFlagged() {
        return flagged;
    }

    /**
     * Marks the adjustment as unusual.
     *
     * @param flagged the new flag
     */
    public void setFlagged(boolean flagged) {
        this.flagged = flagged;
    }

    /**
     * The value of the change at cost.
     *
     * @return the value at cost
     */
    public BigDecimal getTotalValueAtCost() {
        return totalValueAtCost;
    }

    /**
     * Who created the adjustment.
     *
     * @return the user id, or {@code null}
     */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /**
     * When the adjustment was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
