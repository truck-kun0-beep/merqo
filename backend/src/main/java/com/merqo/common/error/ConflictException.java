package com.merqo.common.error;

import org.springframework.http.HttpStatus;

/**
 * Raised when the request contradicts the current state, for example a duplicate payment reference
 * or an illegal status change.
 *
 * <p>Uses inheritance from {@link BusinessException}.
 */
public class ConflictException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a conflict error.
     *
     * @param errorCode stable code such as {@code DUPLICATE_PAYMENT_REFERENCE}
     * @param message a human readable explanation
     */
    public ConflictException(String errorCode, String message) {
        super(errorCode, HttpStatus.CONFLICT.value(), message);
    }
}
