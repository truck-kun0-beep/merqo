package com.merqo.transfer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.merqo.transfer.domain.TransferStatus;

/**
 * Reads and writes the transfers.
 *
 * <p>Responsibility: the only place that writes SQL about the transfers.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface StockTransferRepository extends JpaRepository<StockTransfer, UUID> {

    /**
     * The transfers that touch one of the given locations.
     *
     * <p>A transfer is visible to anybody with TRANSFER_VIEW at its source or at its destination.
     * The query asks for both, so the scope is applied by the database and never in memory.
     *
     * @param locationIds the locations the caller may see
     * @param status the state filter, may be {@code null}
     * @return the transfers
     */
    @Query(
            """
            select distinct t from StockTransfer t
            where (t.sourceLocationId in :locationIds or t.destinationLocationId in :locationIds)
              and (:status is null or t.status = :status)
            order by t.createdAt desc
            """)
    List<StockTransfer> findVisible(
            @Param("locationIds") List<UUID> locationIds, @Param("status") TransferStatus status);

    /**
     * The transfers of the demo rows.
     *
     * @return the transfers
     */
    List<StockTransfer> findByDemoKeyNotNull();

    /**
     * The transfers that are still open and that hold a variant.
     *
     * <p>An open transfer is one that may still move the stock: REQUESTED, APPROVED, DISPATCHED or
     * PARTIALLY_RECEIVED. The variant deactivation guard asks this question.
     *
     * @param variantId the variant
     * @return the open transfers
     */
    @Query(
            """
            select distinct t from StockTransfer t, StockTransferItem i
            where t.id = i.transferId and i.variantId = :variantId
              and t.status in (com.merqo.transfer.domain.TransferStatus.REQUESTED,
                               com.merqo.transfer.domain.TransferStatus.APPROVED,
                               com.merqo.transfer.domain.TransferStatus.DISPATCHED,
                               com.merqo.transfer.domain.TransferStatus.PARTIALLY_RECEIVED)
            """)
    List<StockTransfer> findOpenTransfersOfVariant(@Param("variantId") UUID variantId);
}
