package com.merqo.promotion;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The key of a link between a promotion and one location it covers.
 *
 * <p>Responsibility: name the two columns that together identify one row of {@code
 * promotion_locations}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Value object</b> - the key has no state of its own beyond the two parts.
 *   <li><b>Immutability</b> - the components are final.
 * </ul>
 *
 * @param promotionId the promotion
 * @param locationId the location
 */
public record PromotionLocationId(UUID promotionId, UUID locationId) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The default constructor, needed by JPA. */
    public PromotionLocationId() {
        this(null, null);
    }

    /**
     * Creates the key.
     *
     * @param promotionId the promotion
     * @param locationId the location
     */
    public PromotionLocationId {
        Objects.requireNonNull(promotionId, "The promotion id is required");
        Objects.requireNonNull(locationId, "The location id is required");
    }
}
