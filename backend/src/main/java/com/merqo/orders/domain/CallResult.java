package com.merqo.orders.domain;

/**
 * Result of a staff confirmation call to an order customer.
 *
 * <p>Responsibility: model valid call outcomes together with follow-up transition and note-taking
 * expectations.
 */
public enum CallResult {
    ANSWERED(false, false),
    NO_ANSWER(true, true),
    BUSY(true, true),
    WRONG_NUMBER(false, true),
    CALL_BACK_REQUESTED(true, true);

    private final boolean expectsFollowUp;
    private final boolean requiresNote;

    CallResult(boolean expectsFollowUp, boolean requiresNote) {
        this.expectsFollowUp = expectsFollowUp;
        this.requiresNote = requiresNote;
    }

    /**
     * Whether recording this result moves a {@code PENDING_CONFIRMATION} order to {@code FOLLOW_UP}
     * and schedules a default follow-up timestamp when omitted.
     *
     * @return {@code true} for {@code NO_ANSWER}, {@code BUSY}, and {@code CALL_BACK_REQUESTED}
     */
    public boolean expectsFollowUp() {
        return expectsFollowUp;
    }

    /**
     * Whether a non-blank note is mandatory when logging this result.
     *
     * @return {@code true} for all results except {@code ANSWERED}
     */
    public boolean requiresNote() {
        return requiresNote;
    }
}
