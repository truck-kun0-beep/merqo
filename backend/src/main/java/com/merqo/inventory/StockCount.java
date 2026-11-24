package com.merqo.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One stock count of one location.
 *
 * <p>Responsibility: hold the head of a count: the number, the location, the state and who did
 * what.
 *
 * <p>The state machine of a count has four states: IN_PROGRESS, SUBMITTED, APPLIED and CANCELLED.
 * While the count is in progress the counter does not see the system quantities, so the count is a
 * blind count and the answer of the service omits the numbers the system had.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Enums with behaviour</b> - the state is an enum value, so it can never be a misspelled
 *       string.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 * </ul>
 */
@Entity
@Table(name = "stock_counts")
public class StockCount {

    /** The states a count goes through. */
    public enum Status {
        /** The counter is counting. The system quantities are hidden. */
        IN_PROGRESS,
        /** The counter is done. The variances are visible. */
        SUBMITTED,
        /** The variances were applied to the stock. */
        APPLIED,
        /** The count was called off before it was applied. */
        CANCELLED
    }

    @Id private UUID id;

    @Column(name = "number", nullable = false, length = 20)
    private String number;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "applied_by")
    private UUID appliedBy;

    @Column(name = "is_flagged", nullable = false)
    private boolean flagged;

    /** Required by JPA. */
    protected StockCount() {}

    /**
     * Creates a count.
     *
     * @param id the id
     * @param number the human readable number
     * @param locationId the location
     * @param categoryId the category that is counted, may be {@code null} for the whole location
     * @param createdBy who started it
     * @param createdAt when it was started
     */
    public StockCount(
            UUID id,
            String number,
            UUID locationId,
            UUID categoryId,
            UUID createdBy,
            Instant createdAt) {
        this.id = id;
        this.number = number;
        this.locationId = locationId;
        this.categoryId = categoryId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.status = Status.IN_PROGRESS;
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
     * The human readable number of the count.
     *
     * @return the number
     */
    public String getNumber() {
        return number;
    }

    /**
     * The location of the count.
     *
     * @return the location id
     */
    public UUID getLocationId() {
        return locationId;
    }

    /**
     * The state of the count.
     *
     * @return the status
     */
    public Status getStatus() {
        return status;
    }

    /**
     * The category that is counted, or {@code null} for the whole location.
     *
     * @return the category id
     */
    public UUID getCategoryId() {
        return categoryId;
    }

    /**
     * Who started the count.
     *
     * @return the user id, or {@code null}
     */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /**
     * When the count was started.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the count was submitted.
     *
     * @return the submission moment, or {@code null}
     */
    public Instant getSubmittedAt() {
        return submittedAt;
    }

    /**
     * Who submitted the count.
     *
     * @return the user id, or {@code null}
     */
    public UUID getSubmittedBy() {
        return submittedBy;
    }

    /**
     * Marks the count as submitted.
     *
     * @param submittedBy who submitted it
     * @param submittedAt when it was submitted
     */
    public void markSubmitted(UUID submittedBy, Instant submittedAt) {
        this.status = Status.SUBMITTED;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
    }

    /**
     * When the count was applied.
     *
     * @return the application moment, or {@code null}
     */
    public Instant getAppliedAt() {
        return appliedAt;
    }

    /**
     * Who applied the count.
     *
     * @return the user id, or {@code null}
     */
    public UUID getAppliedBy() {
        return appliedBy;
    }

    /**
     * Marks the count as applied.
     *
     * @param appliedBy who applied it
     * @param appliedAt when it was applied
     */
    public void markApplied(UUID appliedBy, Instant appliedAt) {
        this.status = Status.APPLIED;
        this.appliedBy = appliedBy;
        this.appliedAt = appliedAt;
    }

    /** Marks the count as cancelled. */
    public void markCancelled() {
        this.status = Status.CANCELLED;
    }

    /**
     * Whether the value at cost of the count was above the threshold.
     *
     * @return {@code true} when the count is unusual
     */
    public boolean isFlagged() {
        return flagged;
    }

    /**
     * Marks the count as unusual.
     *
     * @param flagged the new flag
     */
    public void setFlagged(boolean flagged) {
        this.flagged = flagged;
    }
}
