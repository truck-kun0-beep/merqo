package com.merqo.pos;

/**
 * Lifecycle state of a cashier's cash drawer session.
 *
 * <p>Responsibility: restrict cash session states to {@code OPEN} and {@code CLOSED}.
 *
 * <p>OOP concept: <b>Type-safe Enum</b> — eliminates magic status strings across entities, queries,
 * and controllers.
 */
public enum CashSessionStatus {
    OPEN,
    CLOSED
}
