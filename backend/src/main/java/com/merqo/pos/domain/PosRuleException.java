package com.merqo.pos.domain;

/**
 * Framework-free domain exception raised when a POS business rule is violated.
 *
 * <p>Responsibility: carry a stable API error code and suggested HTTP status from pure Java POS
 * domain rules without coupling those rules to Spring or Servlet APIs.
 *
 * <p>OOP concept: <b>Encapsulation &amp; Separation of Concerns</b> — the domain layer signals
 * failures using its own exception type, and the web exception handler translates it into RFC 7807
 * responses.
 */
public class PosRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final int httpStatus;

    /**
     * Creates a POS domain rule exception.
     *
     * @param errorCode stable machine-readable error code
     * @param httpStatus HTTP status code for the API layer
     * @param message human-readable explanation
     */
    public PosRuleException(String errorCode, int httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * Returns the stable API error code.
     *
     * @return the error code
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the HTTP status code associated with this rule failure.
     *
     * @return the HTTP status code
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
