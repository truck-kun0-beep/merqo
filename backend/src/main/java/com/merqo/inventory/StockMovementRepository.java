package com.merqo.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the stock movements.
 *
 * <p>Responsibility: the only place that writes SQL about the movements.
 *
 * <p><b>This interface deliberately has no update and no delete method.</b> The movements are
 * append only. A database trigger raises an error on any UPDATE and on any DELETE, so even a bug in
 * the application cannot rewrite the history of the stock.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface</b> - the contract the service depends on.
 *   <li><b>Encapsulation</b> - the append only rule is part of the shape of the interface, not only
 *       a convention.
 * </ul>
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    /**
     * The movements of one variant at one location, newest first.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the movements
     */
    List<StockMovement> findByLocationIdAndVariantIdOrderByCreatedAtDesc(
            UUID locationId, UUID variantId);

    /**
     * The movements the caller may see, newest first.
     *
     * <p>The order is the creation moment and then the sequence number. The moment alone is not
     * enough: the clock of Windows has a resolution of about fifteen milliseconds, so two movements
     * of the same request can carry the same moment. The sequence number is the tiebreaker.
     *
     * @param locationIds the locations the caller may see
     * @param variantId the variant filter, may be {@code null} for every variant
     * @param pageable the paging
     * @return the page of movements
     */
    @Query(
            """
            select m from StockMovement m
            where m.locationId in :locationIds
              and (:variantId is null or m.variantId = :variantId)
            order by m.createdAt desc, m.seq desc
            """)
    Page<StockMovement> search(
            @Param("locationIds") List<UUID> locationIds,
            @Param("variantId") UUID variantId,
            Pageable pageable);

    /**
     * The movements of one variant at one location that happened at or after a moment.
     *
     * @param locationId the location
     * @param variantId the variant
     * @param since the moment
     * @return the movements
     */
    List<StockMovement> findByLocationIdAndVariantIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
            UUID locationId, UUID variantId, Instant since);

    /**
     * The sum of the deltas of one balance.
     *
     * <p>This is the number the invariant test compares with the stock on the shelf. The sum of the
     * deltas of a balance is always its stock on the shelf, because every change writes exactly one
     * movement.
     *
     * @param locationId the location
     * @param variantId the variant
     * @return the sum of the deltas
     */
    @Query(
            """
            select coalesce(sum(m.quantityDelta), 0) from StockMovement m
            where m.locationId = :locationId and m.variantId = :variantId
            """)
    long sumDelta(@Param("locationId") UUID locationId, @Param("variantId") UUID variantId);

    /**
     * How many movements one balance ever had.
     *
     * <p>The import uses this to decide whether an opening quantity may still be written. A balance
     * that already moved keeps its stock, so importing the same file twice changes nothing the
     * second time.
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
