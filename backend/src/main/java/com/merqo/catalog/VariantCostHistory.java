package com.merqo.catalog;

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
 * One row of the cost history of a variant.
 *
 * <p>Responsibility: hold one cost and the moment it became the cost.
 *
 * <p>The cost is sensitive. It only reaches a user who holds {@code COST_VIEW} or {@code
 * PRICE_EDIT} at the location. For everybody else the field is absent from the answer, not zero.
 *
 * <p>The table is append only, and the current cost is the latest row.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Enums with behaviour</b> - the source of the cost is an enum value.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 * </ul>
 */
@Entity
@Table(name = "variant_cost_history")
public class VariantCostHistory {

    /** Where a cost came from. */
    public enum SourceType {
        /** A person changed the cost on the screen. */
        MANUAL,
        /** The cost came from a CSV import. */
        IMPORT,
        /** The cost came from a purchase. Used from Phase 7. */
        PURCHASE
    }

    @Id private UUID id;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "value", nullable = false, precision = 14, scale = 2)
    private BigDecimal value;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "set_by")
    private UUID setBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected VariantCostHistory() {}

    /**
     * Creates a history row.
     *
     * @param id the id
     * @param variantId the variant
     * @param value the cost
     * @param effectiveFrom the moment the cost became the cost
     * @param sourceType where the cost came from
     * @param sourceId the id of the source, may be {@code null}
     * @param setBy who set the cost
     * @param createdAt when the row was created
     */
    public VariantCostHistory(
            UUID id,
            UUID variantId,
            BigDecimal value,
            Instant effectiveFrom,
            SourceType sourceType,
            UUID sourceId,
            UUID setBy,
            Instant createdAt) {
        this.id = id;
        this.variantId = variantId;
        this.value = value;
        this.effectiveFrom = effectiveFrom;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.setBy = setBy;
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
     * The variant the cost belongs to.
     *
     * @return the variant id
     */
    public UUID getVariantId() {
        return variantId;
    }

    /**
     * The cost.
     *
     * @return the cost
     */
    public BigDecimal getValue() {
        return value;
    }

    /**
     * The moment the cost became the cost.
     *
     * @return the effective moment
     */
    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    /**
     * Where the cost came from.
     *
     * @return the source type
     */
    public SourceType getSourceType() {
        return sourceType;
    }

    /**
     * The id of the source, may be {@code null}.
     *
     * @return the source id
     */
    public UUID getSourceId() {
        return sourceId;
    }

    /**
     * Who set the cost.
     *
     * @return the user id
     */
    public UUID getSetBy() {
        return setBy;
    }

    /**
     * When the row was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
