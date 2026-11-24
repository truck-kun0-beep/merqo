package com.merqo.catalog.domain;

/**
 * The channel a promotion applies to.
 *
 * <p>Responsibility: name the channels a promotion may cover.
 *
 * <p>OOP concept: an enum with behaviour. The {@link #overlaps(Scope)} method decides whether two
 * scopes share a channel, which is one of the four conditions of the conflict detector.
 */
public enum Scope {

    /** The branch counter. */
    POS,

    /** The orders of the website. */
    ONLINE,

    /** Both channels at once. */
    BOTH;

    /**
     * Whether this scope and another one share a channel.
     *
     * <p>{@code BOTH} overlaps everything, and the same channel always overlaps itself.
     *
     * @param other the other scope
     * @return {@code true} when the two scopes share a channel
     */
    public boolean overlaps(Scope other) {
        if (other == null) {
            return false;
        }
        return this == BOTH || other == BOTH || this == other;
    }

    /**
     * Whether this scope covers a channel.
     *
     * @param channel the channel
     * @return {@code true} when the scope covers the channel
     */
    public boolean covers(Scope channel) {
        return channel == BOTH || this == BOTH || this == channel;
    }
}
