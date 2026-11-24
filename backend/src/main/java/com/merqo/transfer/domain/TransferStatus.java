package com.merqo.transfer.domain;

/**
 * The states a stock transfer goes through.
 *
 * <p>Responsibility: name every state of a transfer. The rules about which state may follow which
 * one live in {@link TransferTransitions}, so there is exactly one transition table in the code
 * base.
 *
 * <p>OOP concept: a closed enum. A switch over it is always exhaustive, so a new state cannot be
 * forgotten in one of the branches.
 */
public enum TransferStatus {

    /** The transfer was asked for and waits for a decision. */
    REQUESTED,

    /** The transfer was approved. No stock has moved yet. */
    APPROVED,

    /** The transfer was refused. */
    REJECTED,

    /** The stock left the source. The goods are in transit. */
    DISPATCHED,

    /** Some of the stock arrived at the destination. */
    PARTIALLY_RECEIVED,

    /** All of the stock arrived at the destination. */
    RECEIVED,

    /** The transfer was closed with a shortage: some of the goods never arrived. */
    CLOSED_WITH_SHORTAGE,

    /** The transfer was called off before anything was dispatched. */
    CANCELLED;

    /**
     * Whether the transfer is finished, so no further action is possible.
     *
     * @return {@code true} when the transfer is in a final state
     */
    public boolean isFinal() {
        return this == REJECTED
                || this == RECEIVED
                || this == CLOSED_WITH_SHORTAGE
                || this == CANCELLED;
    }

    /**
     * Whether the goods are on the way.
     *
     * @return {@code true} when the stock has left the source but is not fully received
     */
    public boolean isInTransit() {
        return this == DISPATCHED || this == PARTIALLY_RECEIVED;
    }
}
