package com.merqo.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the counts.
 *
 * <p>Responsibility: the only place that writes SQL about the counts.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface StockCountRepository extends JpaRepository<StockCount, UUID> {

    /**
     * The counts of a location, newest first.
     *
     * @param locationId the location
     * @return the counts
     */
    List<StockCount> findByLocationIdOrderByCreatedAtDesc(UUID locationId);

    /**
     * The counts of a set of locations, newest first.
     *
     * @param locationIds the locations
     * @return the counts
     */
    List<StockCount> findByLocationIdInOrderByCreatedAtDesc(List<UUID> locationIds);

    /**
     * The count that is in progress at one location, if there is one.
     *
     * @param locationId the location
     * @return the open count, if there is one
     */
    java.util.Optional<StockCount> findFirstByLocationIdAndStatus(
            UUID locationId, StockCount.Status status);
}
