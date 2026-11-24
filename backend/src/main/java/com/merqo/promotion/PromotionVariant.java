package com.merqo.promotion;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * The link between a promotion and one variant it covers.
 *
 * <p>Responsibility: hold one link of the promotion to a variant.
 *
 * <p>The link is written when the promotion is created and never changed. A later variant is not
 * covered automatically, and that decision is written down in ASSUMPTIONS.md.
 *
 * <p>The two columns together identify the row, so the key is a separate {@link PromotionVariantId}
 * value object.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Value object</b> - the key is the pair of ids, not a made up id of its own.
 *   <li><b>Composition</b> - the promotion holds its variants through these links instead of
 *       copying them.
 * </ul>
 */
@Entity
@Table(name = "promotion_variants")
@IdClass(PromotionVariantId.class)
public class PromotionVariant {

    @Id
    @Column(name = "promotion_id", nullable = false)
    private UUID promotionId;

    @Id
    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    /** Required by JPA. */
    protected PromotionVariant() {}

    /**
     * Creates a link.
     *
     * @param promotionId the promotion
     * @param variantId the variant
     */
    public PromotionVariant(UUID promotionId, UUID variantId) {
        this.promotionId = promotionId;
        this.variantId = variantId;
    }

    /**
     * The key of the link.
     *
     * @return the key
     */
    public PromotionVariantId getId() {
        return new PromotionVariantId(promotionId, variantId);
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
     * The variant.
     *
     * @return the variant id
     */
    public UUID getVariantId() {
        return variantId;
    }
}
