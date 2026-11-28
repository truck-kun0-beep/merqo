package com.merqo.orders.domain;

/**
 * Reason staff cancelled an order ({@code PENDING_CONFIRMATION}, {@code FOLLOW_UP}, or {@code
 * CONFIRMED}).
 *
 * <p>Responsibility: standardize cancellation categories and enforce mandatory notes for {@code
 * OTHER}.
 */
public enum CancelReason {
    CUSTOMER_REQUESTED(false),
    STOCK_PROBLEM(false),
    ADDRESS_PROBLEM(false),
    OTHER(true);

    private final boolean requiresNote;

    CancelReason(boolean requiresNote) {
        this.requiresNote = requiresNote;
    }

    /**
     * Whether choosing this reason requires a non-blank note.
     *
     * @return {@code true} when {@code OTHER}
     */
    public boolean requiresNote() {
        return requiresNote;
    }
}
