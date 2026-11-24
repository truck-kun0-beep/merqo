package com.merqo.transfer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the lines of the transfers.
 *
 * <p>Responsibility: the only place that writes SQL about the lines of a transfer.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - Spring Data builds the implementation.
 *   <li><b>Abstraction</b> - the services depend on this contract, not on Spring Data.
 * </ul>
 */
public interface StockTransferItemRepository extends JpaRepository<StockTransferItem, UUID> {

    /**
     * The lines of one transfer, ordered by the variant.
     *
     * @param transferId the transfer
     * @return the lines
     */
    List<StockTransferItem> findByTransferIdOrderByVariantIdAsc(UUID transferId);

    /**
     * The lines of several transfers.
     *
     * @param transferIds the transfers
     * @return the lines
     */
    @Query(
            """
            select i from StockTransferItem i
            where i.transferId in :transferIds
            order by i.transferId asc, i.variantId asc
            """)
    List<StockTransferItem> findByTransferIdInOrderByTransferIdAscVariantIdAsc(
            @Param("transferIds") List<UUID> transferIds);
}
