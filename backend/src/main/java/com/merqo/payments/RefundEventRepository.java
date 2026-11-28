package com.merqo.payments;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.merqo.payments.domain.RefundPayoutMethod;

/** Append-only repository for {@link RefundEvent}. */
public interface RefundEventRepository extends JpaRepository<RefundEvent, UUID> {

    List<RefundEvent> findByRefundIdOrderByCreatedAtAsc(UUID refundId);

    List<RefundEvent> findByRefundIdInOrderByCreatedAtAsc(Collection<UUID> refundIds);

    boolean existsByPayoutMethodAndPayoutReference(
            RefundPayoutMethod payoutMethod, String payoutReference);
}
