package com.merqo.pos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Append-only persistence contract for {@link SaleReturn}.
 *
 * <p>Responsibility: read and insert returns, look up idempotent replays and demo keys, and
 * allocate {@code RET-000001} sequence numbers.
 *
 * <p>OOP concept: <b>Repository Abstraction &amp; Append-Only Contract</b> — exposes no update or
 * delete operations.
 */
public interface SaleReturnRepository extends JpaRepository<SaleReturn, UUID> {

    List<SaleReturn> findBySaleIdOrderByCreatedAtDesc(UUID saleId);

    Optional<SaleReturn> findByCreatedByAndIdempotencyKey(UUID createdBy, String idempotencyKey);

    Optional<SaleReturn> findByDemoKey(String demoKey);

    @Query(value = "SELECT nextval('return_number_sequence')", nativeQuery = true)
    long nextReturnNumberSeq();
}
