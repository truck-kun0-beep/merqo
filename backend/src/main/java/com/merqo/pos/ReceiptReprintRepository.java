package com.merqo.pos;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only persistence contract for {@link ReceiptReprint}.
 *
 * <p>Responsibility: log and count duplicate receipt reprints per sale.
 *
 * <p>OOP concept: <b>Repository Abstraction</b> — isolates reprint log persistence.
 */
public interface ReceiptReprintRepository extends JpaRepository<ReceiptReprint, UUID> {

    long countBySaleId(UUID saleId);
}
