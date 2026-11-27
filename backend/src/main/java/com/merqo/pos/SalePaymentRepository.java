package com.merqo.pos;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only persistence contract for {@link SalePayment}.
 *
 * <p>Responsibility: insert and read the single payment record of a sale.
 *
 * <p>OOP concept: <b>Repository Abstraction</b> — isolates payment persistence.
 */
public interface SalePaymentRepository extends JpaRepository<SalePayment, UUID> {

    Optional<SalePayment> findBySaleId(UUID saleId);
}
