package com.merqo.catalog.domain;

import java.time.Instant;

/**
 * A promotion and the two dates that decide when it is alive.
 *
 * <p>Responsibility: hold the identity of a promotion and answer "is it active at this moment?".
 *
 * <p>The expiry is computed from the dates. There is no scheduled job that switches a promotion
 * off, so the answer is always correct at the moment it is asked, even right after a restart.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the dates are private and only the two questions that matter may ask
 *       them.
 *   <li><b>Null Object</b> - an open date range is represented by {@code null}, and the class knows
 *       how to deal with it.
 *   <li><b>Pure function</b> - {@link #isActiveAt(Instant)} depends only on the arguments, so it is
 *       unit tested without a clock and without a database.
 * </ul>
 */
public class Promotion {

    private final java.util.UUID id;
    private final String name;
    private final DiscountType discountType;
    private final java.math.BigDecimal value;
    private final Scope scope;
    private final boolean allLocations;
    private final Instant startsAt;
    private final Instant endsAt;
    private final boolean enabled;

    /**
     * Creates a promotion.
     *
     * @param id the id of the promotion
     * @param name the display name
     * @param discountType the kind of discount
     * @param value the percentage or the amount
     * @param scope the channel the promotion covers
     * @param allLocations whether the promotion covers every location
     * @param startsAt the moment the promotion starts, may be {@code null} for an open start
     * @param endsAt the moment the promotion ends, may be {@code null} for an open end
     * @param enabled whether the promotion is switched on
     */
    public Promotion(
            java.util.UUID id,
            String name,
            DiscountType discountType,
            java.math.BigDecimal value,
            Scope scope,
            boolean allLocations,
            Instant startsAt,
            Instant endsAt,
            boolean enabled) {
        this.id = id;
        this.name = name;
        this.discountType = discountType;
        this.value = value;
        this.scope = scope;
        this.allLocations = allLocations;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.enabled = enabled;
    }

    /**
     * The id of the promotion.
     *
     * @return the id
     */
    public java.util.UUID getId() {
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
     * The kind of discount.
     *
     * @return the kind of discount
     */
    public DiscountType getDiscountType() {
        return discountType;
    }

    /**
     * The percentage or the amount of the discount.
     *
     * @return the value
     */
    public java.math.BigDecimal getValue() {
        return value;
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
     * Whether the promotion covers every location.
     *
     * @return {@code true} when the promotion covers every location
     */
    public boolean isAllLocations() {
        return allLocations;
    }

    /**
     * The moment the promotion starts, or {@code null} for an open start.
     *
     * @return the start
     */
    public Instant getStartsAt() {
        return startsAt;
    }

    /**
     * The moment the promotion ends, or {@code null} for an open end.
     *
     * @return the end
     */
    public Instant getEndsAt() {
        return endsAt;
    }

    /**
     * Whether the promotion is switched on.
     *
     * @return {@code true} when the promotion is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether the promotion is active at a moment.
     *
     * <p>A promotion is active when it is enabled, when it has started and when it has not ended
     * yet. The start and the end are both optional: {@code null} means the range is open in that
     * direction.
     *
     * @param moment the moment to check
     * @return {@code true} when the promotion is active
     */
    public boolean isActiveAt(Instant moment) {
        if (!enabled) {
            return false;
        }
        if (moment == null) {
            return false;
        }
        if (startsAt != null && moment.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || moment.isBefore(endsAt);
    }

    /**
     * Whether the date range of this promotion and another one overlap.
     *
     * <p>An open range overlaps everything. Two ranges that only touch, where one ends exactly when
     * the other starts, do not overlap: the first one is already over at that moment.
     *
     * @param other the other promotion
     * @return {@code true} when the two ranges overlap
     */
    public boolean datesOverlap(Promotion other) {
        if (other == null) {
            return false;
        }
        if (startsAt == null || endsAt == null || other.startsAt == null || other.endsAt == null) {
            // One of the ranges is open, so it covers whatever the other one does.
            return true;
        }
        return startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt);
    }
}
