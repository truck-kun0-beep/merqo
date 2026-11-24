package com.merqo.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.Scope;

/**
 * One promotion of the catalogue.
 *
 * <p>Responsibility: hold the data of one discount: what it gives, which channel it covers, which
 * locations it covers and when it is alive.
 *
 * <p>The variants and the locations the promotion covers live in their own tables, so the promotion
 * itself stays small and the two sets can be queried on their own.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Enums with behaviour</b> - the kind of discount and the scope are enum values.
 *   <li><b>Encapsulation</b> - a promotion is never deleted. It is disabled or ended.
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 * </ul>
 */
@Entity
@Table(name = "promotions")
public class Promotion {

    @Id private UUID id;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "value", nullable = false, precision = 14, scale = 2)
    private BigDecimal value;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private Scope scope;

    @Column(name = "all_locations", nullable = false)
    private boolean allLocations;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "demo_key", length = 60)
    private String demoKey;

    /** Required by JPA. */
    protected Promotion() {}

    /**
     * Creates a promotion.
     *
     * @param id the id
     * @param name the display name
     * @param discountType the kind of discount
     * @param value the percentage or the amount
     * @param scope the channel the promotion covers
     * @param allLocations whether the promotion covers every location
     * @param startsAt the moment it starts, may be {@code null}
     * @param endsAt the moment it ends, may be {@code null}
     * @param enabled whether it is switched on
     * @param note the note, may be {@code null}
     * @param createdBy who created it
     * @param createdAt when it was created
     */
    public Promotion(
            UUID id,
            String name,
            DiscountType discountType,
            BigDecimal value,
            Scope scope,
            boolean allLocations,
            Instant startsAt,
            Instant endsAt,
            boolean enabled,
            String note,
            UUID createdBy,
            Instant createdAt) {
        this.id = id;
        this.name = name;
        this.discountType = discountType;
        this.value = value;
        this.scope = scope;
        this.allLocations = allLocations;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.enabled = enabled;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * The id of the promotion.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The display name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the display name.
     *
     * @param name the name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * The kind of discount.
     *
     * @return the kind of discount
     */
    public DiscountType getDiscountType() {
        return discountType;
    }

    /**
     * Sets the kind of discount.
     *
     * @param discountType the kind of discount
     */
    public void setDiscountType(DiscountType discountType) {
        this.discountType = discountType;
    }

    /**
     * The percentage or the amount of the discount.
     *
     * @return the value
     */
    public BigDecimal getValue() {
        return value;
    }

    /**
     * Sets the value of the discount.
     *
     * @param value the value
     */
    public void setValue(BigDecimal value) {
        this.value = value;
    }

    /**
     * The channel the promotion covers.
     *
     * @return the scope
     */
    public Scope getScope() {
        return scope;
    }

    /**
     * Sets the channel the promotion covers.
     *
     * @param scope the scope
     */
    public void setScope(Scope scope) {
        this.scope = scope;
    }

    /**
     * Whether the promotion covers every location.
     *
     * @return {@code true} when it covers every location
     */
    public boolean isAllLocations() {
        return allLocations;
    }

    /**
     * Sets the flag that says the promotion covers every location.
     *
     * @param allLocations the new flag
     */
    public void setAllLocations(boolean allLocations) {
        this.allLocations = allLocations;
    }

    /**
     * The moment the promotion starts, or {@code null}.
     *
     * @return the start
     */
    public Instant getStartsAt() {
        return startsAt;
    }

    /**
     * Sets the moment the promotion starts.
     *
     * @param startsAt the start
     */
    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    /**
     * The moment the promotion ends, or {@code null}.
     *
     * @return the end
     */
    public Instant getEndsAt() {
        return endsAt;
    }

    /**
     * Sets the moment the promotion ends.
     *
     * @param endsAt the end
     */
    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    /**
     * Whether the promotion is switched on.
     *
     * @return {@code true} when it is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Switches the promotion on or off.
     *
     * @param enabled the new state
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * The note of the promotion.
     *
     * @return the note, or {@code null}
     */
    public String getNote() {
        return note;
    }

    /**
     * Sets the note of the promotion.
     *
     * @param note the note
     */
    public void setNote(String note) {
        this.note = note;
    }

    /**
     * Who created the promotion.
     *
     * @return the creator id
     */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /**
     * The stable key of the demo row.
     *
     * @return the demo key, or {@code null}
     */
    public String getDemoKey() {
        return demoKey;
    }

    /**
     * Sets the demo key.
     *
     * @param demoKey the demo key
     */
    public void setDemoKey(String demoKey) {
        this.demoKey = demoKey;
    }

    /**
     * When the promotion was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * When the promotion was last changed.
     *
     * @return the update moment
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that the promotion was changed.
     *
     * @param updatedAt when it happened
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * The domain view of this promotion.
     *
     * <p>The pure Java {@link com.merqo.catalog.domain.Promotion} carries the rules about the dates
     * and the scope, and it has no Spring and no database in it, so it is unit tested on its own.
     *
     * @return the domain promotion
     */
    public com.merqo.catalog.domain.Promotion toDomain() {
        return new com.merqo.catalog.domain.Promotion(
                id, name, discountType, value, scope, allLocations, startsAt, endsAt, enabled);
    }
}
