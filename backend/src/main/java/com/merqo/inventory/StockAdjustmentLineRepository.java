package com.merqo.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the lines of the adjustments.
 *
 * <p>Responsibility: the only place that writes SQL about the lines of an adjustment.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface StockAdjustmentLineRepository extends JpaRepository<StockAdjustmentLine, UUID> {

    /**
     * The lines of one adjustment.
     *
     * @param adjustmentId the adjustment
     * @return the lines
     */
    List<StockAdjustmentLine> findByAdjustmentId(UUID adjustmentId);
}
