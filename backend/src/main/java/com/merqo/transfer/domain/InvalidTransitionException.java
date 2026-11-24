package com.merqo.transfer.domain;

/**
 * Raised when a transfer is asked to change into a state it may not have.
 *
 * <p>Uses inheritance from {@link RuntimeException}. The transfer service catches it and answers
 * with the error code {@code INVALID_TRANSITION} and the status 409, so a double submit of a
 * dispatch is answered with a clear message instead of a stack trace.
 *
 * <p>OOP concept: a domain exception with a name that says exactly which rule was broken.
 */
public class InvalidTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message a human readable explanation
     */
    public InvalidTransitionException(String message) {
        super(message);
    }
}
