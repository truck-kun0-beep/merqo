package com.merqo.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the cost history.
 *
 * <p>Responsibility: the only place that writes SQL about the cost history.
 *
 * <p><b>There is no update method and no delete method.</b> The table is append only, and the type
 * itself says so: a caller cannot rewrite the history even by accident.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface as the guard</b> - the append only rule is part of the type.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface VariantCostHistoryRepository extends JpaRepository<VariantCostHistory, UUID> {

    /**
     * The cost history of one variant, newest first.
     *
     * @param variantId the variant
     * @return the rows
     */
    List<VariantCostHistory> findByVariantIdOrderByEffectiveFromDesc(UUID variantId);

    /**
     * The latest cost of one variant.
     *
     * @param variantId the variant
     * @return the row, if there is one
     */
    default java.util.Optional<VariantCostHistory> findLatest(UUID variantId) {
        List<VariantCostHistory> rows = findByVariantIdOrderByEffectiveFromDesc(variantId);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }
}
