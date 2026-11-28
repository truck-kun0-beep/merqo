package com.merqo.orders.domain;

/**
 * Framework-free domain exception raised when an order, availability, or integration rule fails.
 *
 * <p>Responsibility: carry a stable API error code and suggested HTTP status from pure Java domain
 * classes without coupling them to Spring or Servlet APIs.
 *
 * <p>OOP concept: <b>Separation of Concerns &amp; Encapsulation</b> — domain rules raise a typed
 * exception carrying an error code and HTTP status, which the web exception handler turns into an
 * RFC 7807 response.
 */
public class OrderRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final int httpStatus;

    /**
     * Creates an order domain rule exception.
     *
     * @param errorCode stable API error code
     * @param httpStatus HTTP status code for the API layer
     * @param message human-readable explanation
     */
    public OrderRuleException(String errorCode, int httpStatus, String message) {
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
     * Returns the HTTP status associated with this failure.
     *
     * @return the HTTP status
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
