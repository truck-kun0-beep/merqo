package com.merqo.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the adjustments.
 *
 * <p>Responsibility: the only place that writes SQL about the adjustments.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, UUID> {

    /**
     * The adjustments of a location, newest first.
     *
     * @param locationId the location
     * @return the adjustments
     */
    List<StockAdjustment> findByLocationIdOrderByCreatedAtDesc(UUID locationId);

    /**
     * The adjustments of a set of locations, newest first.
     *
     * @param locationIds the locations
     * @return the adjustments
     */
    List<StockAdjustment> findByLocationIdInOrderByCreatedAtDesc(List<UUID> locationIds);

    /**
     * The adjustments of the demo rows.
     *
     * @return the adjustments
     */
    List<StockAdjustment> findAll();
}
