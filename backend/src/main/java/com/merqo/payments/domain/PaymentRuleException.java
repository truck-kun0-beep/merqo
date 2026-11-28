package com.merqo.payments.domain;

/**
 * Framework-free domain exception raised when an order payment, refund, or COD rule fails.
 *
 * <p>Responsibility: carry a stable API error code and suggested HTTP status from pure Java payment
 * rules without coupling domain classes to Spring or Servlet APIs.
 *
 * <p>OOP concept: <b>Separation of Concerns &amp; Encapsulation</b> — payment domain rules report
 * typed failures using immutable error metadata translated once at the web boundary.
 */
public class PaymentRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final int httpStatus;

    /**
     * Creates a payment domain rule exception.
     *
     * @param errorCode stable API error code
     * @param httpStatus HTTP status code for the API layer
     * @param message human-readable explanation
     */
    public PaymentRuleException(String errorCode, int httpStatus, String message) {
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
