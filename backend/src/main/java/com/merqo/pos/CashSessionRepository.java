package com.merqo.pos;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistence contract for {@link CashSession}.
 *
 * <p>Responsibility: query cash sessions by cashier, location, demo key, and status, and allocate
 * sequence numbers for {@code CS-000001} codes.
 *
 * <p>OOP concept: <b>Repository Abstraction</b> — isolates SQL/JPA queries behind a typed data
 * access contract.
 */
public interface CashSessionRepository extends JpaRepository<CashSession, UUID> {

    Optional<CashSession> findByCashierIdAndStatus(UUID cashierId, CashSessionStatus status);

    Optional<CashSession> findByCashierIdAndLocationIdAndStatus(
            UUID cashierId, UUID locationId, CashSessionStatus status);

    Optional<CashSession> findByIdAndLocationIdIn(UUID id, Collection<UUID> locationIds);

    Optional<CashSession> findByDemoKey(String demoKey);

    List<CashSession> findByLocationIdInOrderByOpenedAtDesc(Collection<UUID> locationIds);

    @Query(value = "SELECT nextval('cash_session_number_sequence')", nativeQuery = true)
    long nextSessionNumberSeq();
}
