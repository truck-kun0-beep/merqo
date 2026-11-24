package com.merqo.inventory.domain;

/**
 * Raised when there is not enough stock for what was asked.
 *
 * <p>Uses inheritance from {@link RuntimeException}, so the caller is not forced to catch it, and
 * the service layer turns it into the error code {@code INSUFFICIENT_STOCK}.
 *
 * <p>OOP concept: a domain exception with a name that says what went wrong. The inventory service
 * catches it and answers with a 409, so the client never sees a stack trace.
 */
public class InsufficientStockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message a human readable explanation
     */
    public InsufficientStockException(String message) {
        super(message);
    }
}
