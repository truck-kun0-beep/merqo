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

/** Append-only repository for {@link OrderPayment}. */
public interface OrderPaymentRepository extends JpaRepository<OrderPayment, UUID> {

    @Query(value = "SELECT nextval('payment_number_sequence')", nativeQuery = true)
    long nextPaymentSequence();

    List<OrderPayment> findByOrderIdOrderByRecordedAtAsc(UUID orderId);

    Optional<OrderPayment> findByDemoKey(String demoKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from OrderPayment p where p.id = :id")
    Optional<OrderPayment> findByIdForUpdate(@Param("id") UUID id);

    @Query(
            """
            select p from OrderPayment p
            join com.merqo.orders.Order o on o.id = p.orderId
            where o.locationId in :locations
              and not exists (select d.id from PaymentDecisionRecord d where d.paymentId = p.id)
            order by p.recordedAt asc
            """)
    List<OrderPayment> findPendingByLocationIds(@Param("locations") Collection<UUID> locations);

    @Query(
            """
            select count(p) from OrderPayment p
            join com.merqo.orders.Order o on o.id = p.orderId
            where o.locationId in :locations
              and not exists (select d.id from PaymentDecisionRecord d where d.paymentId = p.id)
            """)
    long countPendingByLocationIds(@Param("locations") Collection<UUID> locations);
}
