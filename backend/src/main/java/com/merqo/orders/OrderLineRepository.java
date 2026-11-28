package com.merqo.orders;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.merqo.orders.domain.OrderStatus;

/** JPA repository for {@link OrderLine}. */
public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {

    List<OrderLine> findByOrderIdOrderByLineIndexAsc(UUID orderId);

    List<OrderLine> findByOrderIdIn(Collection<UUID> orderIds);

    @Query(
            """
            select coalesce(sum(l.quantity), 0)
            from OrderLine l, Order o
            where l.orderId = o.id
              and o.locationId = :warehouseId
              and l.variantId = :variantId
              and o.status in :statuses
            """)
    int sumQuantityByLocationVariantAndStatuses(
            @Param("warehouseId") UUID warehouseId,
            @Param("variantId") UUID variantId,
            @Param("statuses") Collection<OrderStatus> statuses);
}
