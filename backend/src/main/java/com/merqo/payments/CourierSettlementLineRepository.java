package com.merqo.payments;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only repository for {@link CourierSettlementLine}. */
public interface CourierSettlementLineRepository
        extends JpaRepository<CourierSettlementLine, UUID> {

    List<CourierSettlementLine> findBySettlementIdOrderByOrderIdAsc(UUID settlementId);

    List<CourierSettlementLine> findBySettlementIdIn(Collection<UUID> settlementIds);

    List<CourierSettlementLine> findByOrderId(UUID orderId);
}
