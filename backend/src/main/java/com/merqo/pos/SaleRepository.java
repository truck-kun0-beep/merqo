package com.merqo.pos;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Append-only persistence contract for {@link Sale}.
 *
 * <p>Responsibility: read and insert POS sales, enforce scope filters in queries, and allocate
 * receipt number sequences. Exposes no update or delete operations.
 *
 * <p>OOP concept: <b>Repository Abstraction &amp; Append-Only Contract</b> — callers cannot mutate
 * or delete sales through this interface.
 */
public interface SaleRepository extends JpaRepository<Sale, UUID> {

    Optional<Sale> findByCashierIdAndIdempotencyKey(UUID cashierId, String idempotencyKey);

    Optional<Sale> findByIdAndLocationIdIn(UUID id, Collection<UUID> locationIds);

    Optional<Sale> findByReceiptNumberIgnoreCaseAndLocationIdIn(
            String receiptNumber, Collection<UUID> locationIds);

    Optional<Sale> findByDemoKey(String demoKey);

    List<Sale> findByCashSessionId(UUID cashSessionId);

    @Query(
            """
            select s from Sale s
            where s.locationId in :locationIds
              and (:onlyCashier = false or s.cashierId = :cashierId)
              and (:hasFrom = false or s.createdAt >= :fromInstant)
              and (:hasTo = false or s.createdAt < :toInstant)
              and (coalesce(:q, '') = ''
                   or lower(s.receiptNumber) like lower(concat('%', :q, '%')))
            order by s.createdAt desc
            """)
    Page<Sale> search(
            @Param("locationIds") Collection<UUID> locationIds,
            @Param("onlyCashier") boolean onlyCashier,
            @Param("cashierId") UUID cashierId,
            @Param("hasFrom") boolean hasFrom,
            @Param("fromInstant") Instant fromInstant,
            @Param("hasTo") boolean hasTo,
            @Param("toInstant") Instant toInstant,
            @Param("q") String q,
            Pageable pageable);

    @Query(value = "SELECT nextval('receipt_number_sequence')", nativeQuery = true)
    long nextReceiptNumberSeq();
}
