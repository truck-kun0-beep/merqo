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
 * One row of the price history of a variant.
 *
 * <p>Responsibility: hold one price and the moment it became the price.
 *
 * <p>The table is append only. A database trigger raises an error on any UPDATE and on any DELETE,
 * and the repository has no update and no delete method. The current price is also kept on the
 * variant, and both are written in the same transaction.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Enums with behaviour</b> - the source of the price is an enum value.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 * </ul>
 */
@Entity
@Table(name = "variant_price_history")
public class VariantPriceHistory {

    /** Where a price came from. */
    public enum SourceType {
        /** A person changed the price on the screen. */
        MANUAL,
        /** The price came from a CSV import. */
        IMPORT,
        /** The price came from a purchase. Used from Phase 7. */
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
    protected VariantPriceHistory() {}

    /**
     * Creates a history row.
     *
     * @param id the id
     * @param variantId the variant
     * @param value the price
     * @param effectiveFrom the moment the price became the price
     * @param sourceType where the price came from
     * @param sourceId the id of the source, may be {@code null}
     * @param setBy who set the price
     * @param createdAt when the row was created
     */
    public VariantPriceHistory(
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
     * The variant the price belongs to.
     *
     * @return the variant id
     */
    public UUID getVariantId() {
        return variantId;
    }

    /**
     * The price.
     *
     * @return the price
     */
    public BigDecimal getValue() {
        return value;
    }

    /**
     * The moment the price became the price.
     *
     * @return the effective moment
     */
    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    /**
     * Where the price came from.
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
     * Who set the price.
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
