package com.merqo.orders.domain;

/**
 * Channel through which an order entered Merqo. POS counter sales are tracked separately in {@code
 * sales} and never use this enum.
 *
 * <p>Responsibility: distinguish automated website orders ({@code ONLINE}) from phone/social orders
 * entered by staff ({@code MANUAL}).
 *
 * <p>OOP concept: <b>Type safety</b> — closed enum instead of unchecked strings.
 */
public enum OrderChannel {
    ONLINE,
    MANUAL
}
