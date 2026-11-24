package com.merqo.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the lines of the counts.
 *
 * <p>Responsibility: the only place that writes SQL about the lines of a count.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface StockCountLineRepository extends JpaRepository<StockCountLine, UUID> {

    /**
     * The lines of one count.
     *
     * @param countId the count
     * @return the lines
     */
    List<StockCountLine> findByCountId(UUID countId);

    /**
     * One line of a count.
     *
     * @param countId the count
     * @param variantId the variant
     * @return the line, if there is one
     */
    java.util.Optional<StockCountLine> findByCountIdAndVariantId(UUID countId, UUID variantId);
}
