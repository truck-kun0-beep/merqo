package com.merqo.payments.domain;

import java.util.Collection;

/**
 * Derived lifecycle status of a refund request.
 *
 * <p>Responsibility: compute refund state strictly from its append-only {@code refund_events} log
 * rather than mutating a stored status column.
 *
 * <p>OOP concept: <b>Derived Value &amp; Encapsulation</b> — encapsulates event-to-state derivation
 * in one place.
 */
public enum RefundStatus {
    REQUESTED(false),
    APPROVED(false),
    REJECTED(true),
    PAID(true);

    /** Immutable event names stored in {@code refund_events.event_type}. */
    public enum RefundEventType {
        APPROVED,
        REJECTED,
        PAID
    }

    private final boolean terminal;

    RefundStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /**
     * Whether this refund status is terminal and admits no further transitions.
     *
     * @return {@code true} for {@code REJECTED} and {@code PAID}
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Whether this refund currently reserves funds against future refund requests ({@code
     * REQUESTED} or {@code APPROVED}).
     *
     * @return {@code true} if still open and unpaid
     */
    public boolean reservesRefundableBalance() {
        return this == REQUESTED || this == APPROVED;
    }

    /**
     * Derives current status from recorded immutable {@link RefundEventType} entries.
     *
     * @param events recorded events for one refund request
     * @return derived status
     */
    public static RefundStatus derive(Collection<RefundEventType> events) {
        if (events == null || events.isEmpty()) {
            return REQUESTED;
        }
        if (events.contains(RefundEventType.PAID)) {
            return PAID;
        }
        if (events.contains(RefundEventType.REJECTED)) {
            return REJECTED;
        }
        if (events.contains(RefundEventType.APPROVED)) {
            return APPROVED;
        }
        return REQUESTED;
    }
}
