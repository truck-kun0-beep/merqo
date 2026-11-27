package com.merqo.pos;

/**
 * Stock disposition chosen when items are returned at the POS.
 *
 * <p>Responsibility: indicate whether returned units return directly to sellable stock or enter
 * inspection.
 *
 * <p>OOP concept: <b>Type-safe Enum</b> — keeps return stock routing explicit.
 */
public enum ReturnDisposition {
    RESTOCK,
    INSPECTION
}
