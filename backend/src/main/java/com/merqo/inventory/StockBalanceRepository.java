package com.merqo.inventory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the stock balances.
 *
 * <p>Responsibility: the only place that writes SQL about the balances.
 *
 * <p>The row is read with a pessimistic lock when a movement is about to change it, so two requests
 * that touch the same stock wait for each other instead of overwriting each other.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 *   <li><b>Strategy</b> - the lock is part of the contract, so the caller cannot forget it.
 * </ul>
 */
public interface StockBalanceRepository extends JpaRepository<StockBalance, UUID> {

    /**
     * The balance of one variant at one location.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the balance, if there is one
     */
    Optional<StockBalance> findByLocationIdAndVariantId(UUID locationId, UUID variantId);

    /**
     * The balance of one variant at one location, locked for writing.
     *
     * <p>The lock is what stops two requests from taking the same last piece. The row is locked
     * until the transaction ends, so the second request reads the new number and not the old one.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the balance, if there is one
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select b from StockBalance b where b.locationId = :locationId and b.variantId = :variantId")
    Optional<StockBalance> findForUpdate(
            @Param("locationId") UUID locationId, @Param("variantId") UUID variantId);

    /**
     * The balances of the given variants at the given locations.
     *
     * @param locationIds the locations
     * @param variantIds the variants
     * @return the balances
     */
    @Query(
            """
            select b from StockBalance b
            where b.locationId in :locationIds and b.variantId in :variantIds
            """)
    List<StockBalance> findByLocationIdsAndVariantIds(
            @Param("locationIds") Set<UUID> locationIds, @Param("variantIds") Set<UUID> variantIds);

    /**
     * The balances of one location, ordered by the variant.
     *
     * @param locationId the location
     * @return the balances
     */
    List<StockBalance> findByLocationIdOrderByVariantIdAsc(UUID locationId);

    /**
     * The balances of a set of locations, ordered by the location and the variant.
     *
     * @param locationIds the locations
     * @return the balances
     */
    List<StockBalance> findByLocationIdInOrderByLocationIdAscVariantIdAsc(Set<UUID> locationIds);

    /**
     * The balances of one variant at the given locations.
     *
     * @param variantId the variant
     * @param locationIds the locations
     * @return the balances
     */
    List<StockBalance> findByVariantIdAndLocationIdIn(UUID variantId, Set<UUID> locationIds);

    /**
     * The number of balances that ever had a movement.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the number of movements
     */
    @Query(
            """
            select count(m) from StockMovement m
            where m.locationId = :locationId and m.variantId = :variantId
            """)
    long countMovements(@Param("locationId") UUID locationId, @Param("variantId") UUID variantId);
}
