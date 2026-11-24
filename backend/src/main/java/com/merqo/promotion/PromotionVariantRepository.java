package com.merqo.promotion;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the variants a promotion covers.
 *
 * <p>Responsibility: the only place that writes SQL about the covered variants.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface PromotionVariantRepository
        extends JpaRepository<PromotionVariant, PromotionVariantId> {

    /**
     * The variants one promotion covers.
     *
     * @param promotionId the promotion
     * @return the covered variants
     */
    List<PromotionVariant> findByPromotionId(UUID promotionId);

    /**
     * The promotions that cover one variant.
     *
     * @param variantId the variant
     * @return the covered promotions
     */
    List<PromotionVariant> findByVariantId(UUID variantId);

    /**
     * The variant ids one promotion covers.
     *
     * @param promotionId the promotion
     * @return the variant ids
     */
    @Query("select pv.variantId from PromotionVariant pv where pv.promotionId = :promotionId")
    List<UUID> findVariantIdsByPromotionId(@Param("promotionId") UUID promotionId);

    /**
     * The variant ids of a list of promotions.
     *
     * @param promotionIds the promotions
     * @return the pairs of promotion and variant
     */
    List<PromotionVariant> findByPromotionIdIn(List<UUID> promotionIds);
}
