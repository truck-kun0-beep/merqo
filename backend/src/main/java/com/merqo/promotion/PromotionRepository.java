package com.merqo.promotion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the promotions.
 *
 * <p>Responsibility: the only place that writes SQL about promotions.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    /**
     * Every promotion, newest first.
     *
     * @return the promotions
     */
    List<Promotion> findAllByOrderByCreatedAtDesc();

    /**
     * The promotions of the demo rows.
     *
     * @return the promotions
     */
    List<Promotion> findByDemoKeyNotNull();

    Optional<Promotion> findByDemoKey(String demoKey);

    /**
     * The promotions that cover a variant.
     *
     * @param variantId the variant
     * @return the promotions
     */
    @Query(
            """
            select p from Promotion p, PromotionVariant pv
            where p.id = pv.promotionId and pv.variantId = :variantId
            """)
    List<Promotion> findByVariantId(@Param("variantId") UUID variantId);

    /**
     * The promotions that are switched on and whose date range covers a moment.
     *
     * @param variantId the variant
     * @param moment the moment
     * @return the promotions
     */
    @Query(
            """
            select p from Promotion p, PromotionVariant pv
            where p.id = pv.promotionId and pv.variantId = :variantId
              and p.enabled = true
              and (p.startsAt is null or p.startsAt <= :moment)
              and (p.endsAt is null or p.endsAt > :moment)
            """)
    List<Promotion> findActiveForVariant(
            @Param("variantId") UUID variantId, @Param("moment") java.time.Instant moment);
}
