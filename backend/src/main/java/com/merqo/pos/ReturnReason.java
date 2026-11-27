package com.merqo.pos;

/**
 * Reason why a customer is returning items from a POS sale.
 *
 * <p>Responsibility: restrict return reasons to the four documented categories.
 *
 * <p>OOP concept: <b>Type-safe Enum</b> — enforces valid return reasons at deserialization and
 * service boundaries.
 */
public enum ReturnReason {
    CHANGED_MIND,
    WRONG_SIZE,
    DEFECTIVE,
    OTHER
}
