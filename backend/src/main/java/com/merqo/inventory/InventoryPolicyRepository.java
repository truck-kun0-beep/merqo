package com.merqo.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the inventory policies.
 *
 * <p>Responsibility: the only place that writes SQL about the policies.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface InventoryPolicyRepository extends JpaRepository<InventoryPolicy, UUID> {

    /**
     * The policy of one variant at one location.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the policy, if there is one
     */
    Optional<InventoryPolicy> findByLocationIdAndVariantId(UUID locationId, UUID variantId);

    /**
     * The policies of a set of locations.
     *
     * @param locationIds the locations
     * @return the policies
     */
    List<InventoryPolicy> findByLocationIdIn(List<UUID> locationIds);

    /**
     * The reorder points of a set of locations, as a map of variant id to point.
     *
     * <p>The query returns the pairs directly, so the caller never has to load the whole policy
     * table into memory to answer the low stock question.
     *
     * @param locationIds the locations
     * @return the pairs of variant id and reorder point
     */
    @Query(
            """
            select p.variantId, p.reorderPoint from InventoryPolicy p
            where p.locationId in :locationIds
            """)
    List<Object[]> findReorderPoints(@Param("locationIds") List<UUID> locationIds);
}
