package com.merqo.payments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.merqo.payments.domain.CodRules.Courier;

/** Append-only repository for {@link CourierSettlement}. */
public interface CourierSettlementRepository extends JpaRepository<CourierSettlement, UUID> {

    @Query(value = "SELECT nextval('settlement_number_sequence')", nativeQuery = true)
    long nextSettlementSequence();

    List<CourierSettlement> findAllByOrderByReceivedOnDescRecordedAtDesc();

    Optional<CourierSettlement> findByCourierAndReferenceIgnoreCase(
            Courier courier, String reference);

    Optional<CourierSettlement> findByDemoKey(String demoKey);
}
