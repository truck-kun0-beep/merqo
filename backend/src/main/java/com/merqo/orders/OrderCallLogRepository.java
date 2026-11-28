package com.merqo.orders;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only repository for {@link OrderCallLog}. Never declares updates or deletes. */
public interface OrderCallLogRepository extends JpaRepository<OrderCallLog, UUID> {

    List<OrderCallLog> findByOrderIdOrderByAttemptNumberAsc(UUID orderId);

    int countByOrderId(UUID orderId);
}
