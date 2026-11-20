package com.merqo.common.error;

import org.springframework.http.HttpStatus;

/**
 * Raised when the requested record does not exist or is not visible to the caller.
 *
 * <p>Uses inheritance from {@link BusinessException} so the API layer reports every expected
 * failure with the same error format.
 */
public class NotFoundException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a not found error.
     *
     * @param message a human readable explanation
     */
    public NotFoundException(String message) {
        super("NOT_FOUND", HttpStatus.NOT_FOUND.value(), message);
    }
}
