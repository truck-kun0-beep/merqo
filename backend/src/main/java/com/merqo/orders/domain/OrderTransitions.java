package com.merqo.orders.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Single authoritative transition table for order statuses in Phase 4.
 *
 * <p>Responsibility: hold all allowed status transitions in one immutable table and reject invalid
 * moves with {@code INVALID_TRANSITION}.
 *
 * <p>OOP concept: <b>Data-driven state machine</b> — transitions are declared once as an immutable
 * {@link EnumMap} rather than duplicated conditional branches across services.
 */
public final class OrderTransitions {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED;

    static {
        EnumMap<OrderStatus, Set<OrderStatus>> map = new EnumMap<>(OrderStatus.class);
        map.put(
                OrderStatus.PENDING_CONFIRMATION,
                Set.of(
                        OrderStatus.FOLLOW_UP,
                        OrderStatus.CONFIRMED,
                        OrderStatus.DECLINED,
                        OrderStatus.CANCELLED));
        map.put(
                OrderStatus.FOLLOW_UP,
                Set.of(OrderStatus.CONFIRMED, OrderStatus.DECLINED, OrderStatus.CANCELLED));
        map.put(OrderStatus.CONFIRMED, Set.of(OrderStatus.CANCELLED));
        map.put(OrderStatus.DECLINED, Set.of());
        map.put(OrderStatus.CANCELLED, Set.of());
        map.put(OrderStatus.READY_TO_SHIP, Set.of());
        map.put(OrderStatus.SHIPPED, Set.of());
        map.put(OrderStatus.DELIVERED, Set.of());
        map.put(OrderStatus.REFUSED, Set.of());
        map.put(OrderStatus.RETURNED, Set.of());
        ALLOWED = Map.copyOf(map);
    }

    private OrderTransitions() {}

    /**
     * Returns whether transitioning from {@code from} to {@code to} is allowed.
     *
     * @param from current order status
     * @param to target order status
     * @return {@code true} when permitted
     */
    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Enforces an allowed transition, throwing {@link OrderRuleException} ({@code
     * INVALID_TRANSITION}, HTTP 409) otherwise.
     *
     * @param from current order status
     * @param to target order status
     */
    public static void requireTransition(OrderStatus from, OrderStatus to) {
        if (!canTransition(from, to)) {
            throw new OrderRuleException(
                    "INVALID_TRANSITION",
                    409,
                    "Order cannot transition from " + from + " to " + to + ".");
        }
    }
}
