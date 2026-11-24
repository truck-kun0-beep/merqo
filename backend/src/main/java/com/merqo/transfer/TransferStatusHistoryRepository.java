package com.merqo.transfer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the status history of the transfers.
 *
 * <p>Responsibility: the only place that writes SQL about the status history.
 *
 * <p><b>This interface deliberately has no update and no delete method.</b> The history is append
 * only. A database trigger raises an error on any UPDATE and on any DELETE, so even a bug in the
 * application cannot rewrite the story of a transfer.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface</b> - the contract the service depends on.
 *   <li><b>Encapsulation</b> - the append only rule is part of the shape of the interface.
 * </ul>
 */
public interface TransferStatusHistoryRepository
        extends JpaRepository<TransferStatusHistory, UUID> {

    /**
     * The history of one transfer, oldest first.
     *
     * @param transferId the transfer
     * @return the rows
     */
    List<TransferStatusHistory> findByTransferIdOrderByCreatedAtAsc(UUID transferId);
}
