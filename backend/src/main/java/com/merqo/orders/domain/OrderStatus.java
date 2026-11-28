package com.merqo.orders.domain;

/**
 * Lifecycle states of an online or manual order.
 *
 * <p>Responsibility: define Phase 4 statuses plus names reserved for Phases 5 and 6, and expose
 * intrinsic inventory behaviors ({@link #isSoftHold()} and {@link #holdsReservation()}).
 *
 * <p>OOP concept: <b>Enums with behavior</b> — stock hold semantics travel on the enum constant
 * instead of scattered status switches.
 */
public enum OrderStatus {
    PENDING_CONFIRMATION(true, false, false),
    FOLLOW_UP(true, false, false),
    CONFIRMED(false, true, false),
    DECLINED(false, false, true),
    CANCELLED(false, false, true),

    // Reserved for Phases 5 and 6 (no Phase 4 transitions enter them).
    READY_TO_SHIP(false, true, false),
    SHIPPED(false, true, false),
    DELIVERED(false, false, true),
    REFUSED(false, false, true),
    RETURNED(false, false, true);

    private final boolean softHold;
    private final boolean holdsReservation;
    private final boolean terminal;

    OrderStatus(boolean softHold, boolean holdsReservation, boolean terminal) {
        this.softHold = softHold;
        this.holdsReservation = holdsReservation;
        this.terminal = terminal;
    }

    /**
     * Whether orders in this status contribute derived soft holds to online availability.
     *
     * @return {@code true} for {@code PENDING_CONFIRMATION} and {@code FOLLOW_UP}
     */
    public boolean isSoftHold() {
        return softHold;
    }

    /**
     * Whether orders in this status hold a hard stock reservation at the warehouse.
     *
     * @return {@code true} when hard inventory reservation is held
     */
    public boolean holdsReservation() {
        return holdsReservation;
    }

    /**
     * Whether no further operational progression occurs from this status.
     *
     * @return {@code true} for terminal statuses
     */
    public boolean isTerminal() {
        return terminal;
    }
}
