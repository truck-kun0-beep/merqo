package com.merqo.orders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for {@link Order}. */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query(value = "SELECT nextval('order_number_sequence')", nativeQuery = true)
    long nextOrderSequence();

    Optional<Order> findByIntegrationClientIdAndExternalOrderId(
            UUID integrationClientId, String externalOrderId);

    Optional<Order> findByOrderNumber(String orderNumber);

    Optional<Order> findByDemoKey(String demoKey);

    List<Order> findByPhoneNormalizedOrderByCreatedAtDesc(String phoneNormalized);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);
}
