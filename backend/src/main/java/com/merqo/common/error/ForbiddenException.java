package com.merqo.common.error;

import org.springframework.http.HttpStatus;

/**
 * Raised when the caller is not allowed to perform the requested action, for example a cashier
 * trying to read the owner dashboard.
 *
 * <p>Uses inheritance from {@link BusinessException}. The backend always checks both the permission
 * and the caller's assigned locations before answering.
 */
public class ForbiddenException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a forbidden error.
     *
     * @param errorCode stable code such as {@code BRANCH_SCOPE_VIOLATION}
     * @param message a human readable explanation
     */
    public ForbiddenException(String errorCode, String message) {
        super(errorCode, HttpStatus.FORBIDDEN.value(), message);
    }
}
