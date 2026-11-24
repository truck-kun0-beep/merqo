package com.merqo.promotion;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * The link between a promotion and one location it covers.
 *
 * <p>Responsibility: hold one link of the promotion to a location.
 *
 * <p>A promotion with {@code allLocations = TRUE} has no links at all: it covers every location.
 *
 * <p>The two columns together identify the row, so the key is a separate {@link
 * PromotionLocationId} value object.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Value object</b> - the key is the pair of ids, not a made up id of its own.
 *   <li><b>Composition</b> - the promotion holds its locations through these links.
 * </ul>
 */
@Entity
@Table(name = "promotion_locations")
@IdClass(PromotionLocationId.class)
public class PromotionLocation {

    @Id
    @Column(name = "promotion_id", nullable = false)
    private UUID promotionId;

    @Id
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    /** Required by JPA. */
    protected PromotionLocation() {}

    /**
     * Creates a link.
     *
     * @param promotionId the promotion
     * @param locationId the location
     */
    public PromotionLocation(UUID promotionId, UUID locationId) {
        this.promotionId = promotionId;
        this.locationId = locationId;
    }

    /**
     * The key of the link.
     *
     * @return the key
     */
    public PromotionLocationId getId() {
        return new PromotionLocationId(promotionId, locationId);
    }

    /**
     * The promotion.
     *
     * @return the promotion id
     */
    public UUID getPromotionId() {
        return promotionId;
    }

    /**
     * The location.
     *
     * @return the location id
     */
    public UUID getLocationId() {
        return locationId;
    }
}
