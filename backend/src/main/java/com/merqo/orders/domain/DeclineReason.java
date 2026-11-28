package com.merqo.orders.domain;

/**
 * Reason staff declined an unconfirmed order ({@code PENDING_CONFIRMATION} or {@code FOLLOW_UP}).
 *
 * <p>Responsibility: standardize decline categories and enforce mandatory notes for {@code OTHER}.
 */
public enum DeclineReason {
    CUSTOMER_UNREACHABLE(false),
    CUSTOMER_DECLINED(false),
    OUT_OF_STOCK(false),
    INVALID_ADDRESS(false),
    SUSPECTED_FAKE(false),
    OTHER(true);

    private final boolean requiresNote;

    DeclineReason(boolean requiresNote) {
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
