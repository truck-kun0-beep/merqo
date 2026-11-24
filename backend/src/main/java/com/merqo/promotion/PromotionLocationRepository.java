package com.merqo.promotion;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the locations a promotion covers.
 *
 * <p>Responsibility: the only place that writes SQL about the covered locations.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface PromotionLocationRepository
        extends JpaRepository<PromotionLocation, PromotionLocationId> {

    /**
     * The locations one promotion covers.
     *
     * @param promotionId the promotion
     * @return the covered locations
     */
    List<PromotionLocation> findByPromotionId(UUID promotionId);

    /**
     * The location ids one promotion covers.
     *
     * @param promotionId the promotion
     * @return the location ids
     */
    @Query("select pl.locationId from PromotionLocation pl where pl.promotionId = :promotionId")
    List<UUID> findLocationIdsByPromotionId(@Param("promotionId") UUID promotionId);

    /**
     * The promotions that cover one location.
     *
     * @param locationId the location
     * @return the covered promotions
     */
    List<PromotionLocation> findByLocationId(UUID locationId);
}
