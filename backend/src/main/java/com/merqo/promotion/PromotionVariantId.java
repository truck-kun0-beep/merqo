package com.merqo.promotion;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The key of a link between a promotion and one variant it covers.
 *
 * <p>Responsibility: name the two columns that together identify one row of {@code
 * promotion_variants}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Value object</b> - the key has no state of its own beyond the two parts.
 *   <li><b>Immutability</b> - the components are final.
 * </ul>
 *
 * @param promotionId the promotion
 * @param variantId the variant
 */
public record PromotionVariantId(UUID promotionId, UUID variantId) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The default constructor, needed by JPA. */
    public PromotionVariantId() {
        this(null, null);
    }

    /**
     * Creates the key.
     *
     * @param promotionId the promotion
     * @param variantId the variant
     */
    public PromotionVariantId {
        Objects.requireNonNull(promotionId, "The promotion id is required");
        Objects.requireNonNull(variantId, "The variant id is required");
    }
}
