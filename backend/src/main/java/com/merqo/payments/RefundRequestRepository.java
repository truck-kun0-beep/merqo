package com.merqo.payments;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only repository for {@link RefundRequest}. */
public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    @Query(value = "SELECT nextval('refund_number_sequence')", nativeQuery = true)
    long nextRefundSequence();

    List<RefundRequest> findByOrderIdOrderByRequestedAtAsc(UUID orderId);

    Optional<RefundRequest> findByDemoKey(String demoKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequest r where r.id = :id")
    Optional<RefundRequest> findByIdForUpdate(@Param("id") UUID id);

    @Query(
            """
            select r from RefundRequest r
            join com.merqo.orders.Order o on o.id = r.orderId
            where o.locationId in :locations
            order by r.requestedAt desc
            """)
    List<RefundRequest> findScopedNewestFirst(@Param("locations") Collection<UUID> locations);
}
