package com.merqo.payments;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only repository for {@link PaymentDecisionRecord}. */
public interface PaymentDecisionRepository extends JpaRepository<PaymentDecisionRecord, UUID> {

    Optional<PaymentDecisionRecord> findByPaymentId(UUID paymentId);

    List<PaymentDecisionRecord> findByPaymentIdIn(Collection<UUID> paymentIds);
}
