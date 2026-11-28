package com.merqo.orders;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.integration.SoftHoldReader;
import com.merqo.orders.domain.OrderStatus;

/** Computes derived unconfirmed soft holds across PENDING_CONFIRMATION and FOLLOW_UP orders. */
@Service
public class OrderSoftHoldReader implements SoftHoldReader {

    private static final List<OrderStatus> SOFT_HOLD_STATUSES =
            List.of(OrderStatus.PENDING_CONFIRMATION, OrderStatus.FOLLOW_UP);

    private final OrderLineRepository orderLineRepository;

    public OrderSoftHoldReader(OrderLineRepository orderLineRepository) {
        this.orderLineRepository = orderLineRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public int softHoldForVariant(UUID warehouseId, UUID variantId) {
        return orderLineRepository.sumQuantityByLocationVariantAndStatuses(
                warehouseId, variantId, SOFT_HOLD_STATUSES);
    }
}
