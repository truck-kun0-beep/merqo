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

    /**
     * Creates a not found error with its own stable code.
     *
     * <p>Most of the not found errors use the plain {@code NOT_FOUND} code, because they mean the
     * same thing: the record is not there or not visible. A few have their own code, such as {@code
     * BARCODE_NOT_FOUND}, so that the frontend can tell the cases apart without reading the
     * message.
     *
     * @param errorCode the stable code, such as {@code BARCODE_NOT_FOUND}
     * @param message a human readable explanation
     */
    public NotFoundException(String errorCode, String message) {
        super(errorCode, HttpStatus.NOT_FOUND.value(), message);
    }
}
