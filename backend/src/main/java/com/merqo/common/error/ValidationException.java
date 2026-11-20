package com.merqo.common.error;

import org.springframework.http.HttpStatus;

/**
 * Raised when the request is well formed but breaks a business rule, for example asking to reserve
 * more stock than exists.
 *
 * <p>Uses inheritance from {@link BusinessException}.
 */
public class ValidationException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a business rule violation.
     *
     * @param errorCode stable code such as {@code INSUFFICIENT_STOCK}
     * @param message a human readable explanation
     */
    public ValidationException(String errorCode, String message) {
        super(errorCode, HttpStatus.UNPROCESSABLE_CONTENT.value(), message);
    }
}
