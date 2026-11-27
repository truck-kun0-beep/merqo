package com.merqo.pos;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only persistence contract for {@link SaleReturnLine}.
 *
 * <p>Responsibility: insert and query return lines by return id or original sale line id.
 *
 * <p>OOP concept: <b>Repository Abstraction</b> — isolates return line lookups.
 */
public interface SaleReturnLineRepository extends JpaRepository<SaleReturnLine, UUID> {

    List<SaleReturnLine> findBySaleReturnId(UUID saleReturnId);

    List<SaleReturnLine> findBySaleLineId(UUID saleLineId);
}
