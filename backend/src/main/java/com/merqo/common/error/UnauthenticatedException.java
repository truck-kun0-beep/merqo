package com.merqo.common.error;

/**
 * Raised when the caller is not signed in, or the session is no longer valid.
 *
 * <p>Uses inheritance from {@link BusinessException}, so the global handler answers with the same
 * problem detail as every other failure.
 */
public class UnauthenticatedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the error.
     *
     * @param message a human readable explanation
     */
    public UnauthenticatedException(String message) {
        super(ErrorCodes.UNAUTHENTICATED, 401, message);
    }

    /** Creates the error with the standard message. */
    public UnauthenticatedException() {
        this("You must sign in to continue.");
    }
}
