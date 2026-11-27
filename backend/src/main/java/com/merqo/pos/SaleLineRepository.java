package com.merqo.pos;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only persistence contract for {@link SaleLine}.
 *
 * <p>Responsibility: insert and read sale lines ordered by {@code lineIndex}.
 *
 * <p>OOP concept: <b>Repository Abstraction</b> — isolates line-item persistence.
 */
public interface SaleLineRepository extends JpaRepository<SaleLine, UUID> {

    List<SaleLine> findBySaleIdOrderByLineIndexAsc(UUID saleId);
}
