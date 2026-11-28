package com.merqo.payments.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure transition table governing allowed refund status moves.
 *
 * <p>Responsibility: permit {@code REQUESTED -> {APPROVED, REJECTED}} and {@code APPROVED -> {PAID,
 * REJECTED}}, treating {@code PAID} and {@code REJECTED} as terminal and throwing {@code 409
 * INVALID_TRANSITION} on any invalid step.
 *
 * <p>OOP concept: <b>Explicit State Machine Table</b> — replaces scattered conditional checks with
 * a single declarative transition map.
 */
public final class RefundTransitions {

    private static final Map<RefundStatus, Set<RefundStatus>> ALLOWED =
            new EnumMap<>(RefundStatus.class);

    static {
        ALLOWED.put(
                RefundStatus.REQUESTED, EnumSet.of(RefundStatus.APPROVED, RefundStatus.REJECTED));
        ALLOWED.put(RefundStatus.APPROVED, EnumSet.of(RefundStatus.PAID, RefundStatus.REJECTED));
        ALLOWED.put(RefundStatus.REJECTED, EnumSet.noneOf(RefundStatus.class));
        ALLOWED.put(RefundStatus.PAID, EnumSet.noneOf(RefundStatus.class));
    }

    private RefundTransitions() {}

    /**
     * Checks whether moving from {@code from} to {@code to} is allowed.
     *
     * @param from current derived status
     * @param to target status
     * @return {@code true} if allowed
     */
    public static boolean canTransition(RefundStatus from, RefundStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Asserts that {@code from -> to} is valid, throwing {@code 409 INVALID_TRANSITION} otherwise.
     *
     * @param from current derived status
     * @param to target status
     */
    public static void requireTransition(RefundStatus from, RefundStatus to) {
        if (!canTransition(from, to)) {
            throw new PaymentRuleException(
                    "INVALID_TRANSITION",
                    409,
                    "Refund cannot move from " + from + " to " + to + ".");
        }
    }
}
