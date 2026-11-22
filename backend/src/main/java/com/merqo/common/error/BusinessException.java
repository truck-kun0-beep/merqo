package com.merqo.common.error;

/**
 * Base class for every error Merqo raises on purpose.
 *
 * <p>Responsibility: carry a stable, machine readable error code and an HTTP status so that the API
 * layer never has to guess how to report a failure.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Inheritance</b> - specific failures such as {@link NotFoundException} and {@link
 *       ConflictException} extend this class, so the global handler can treat every expected
 *       failure the same way.
 *   <li><b>Abstraction</b> - callers work with the abstract idea "a business rule was broken"
 *       without knowing the concrete subclass.
 *   <li><b>Encapsulation</b> - the code, status and detail are set once in the constructor and
 *       exposed through read-only accessors.
 * </ul>
 *
 * <p>Unchecked by design: a broken business rule is not a programming error, so it must not force
 * every caller to catch it.
 */
public abstract class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final int httpStatus;

    /**
     * Creates a business exception.
     *
     * @param errorCode stable code such as {@code VALIDATION_FAILED}
     * @param httpStatus the HTTP status the API should answer with
     * @param message a human readable explanation
     */
    protected BusinessException(String errorCode, int httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * The stable code clients can rely on.
     *
     * @return the error code
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * The HTTP status this error maps to.
     *
     * @return the HTTP status code
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
