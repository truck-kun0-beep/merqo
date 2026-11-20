package com.merqo.common.web;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.merqo.common.error.BusinessException;
import com.merqo.common.error.ProblemDetail;

/**
 * Turns every failure into the one error format the frontend understands.
 *
 * <p>Responsibility: catch exceptions from controllers and services and answer with an RFC 7807
 * problem detail that carries a stable {@code errorCode}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Polymorphism</b> - each {@code @ExceptionHandler} method handles a different type of
 *       failure, and Spring picks the most specific match.
 *   <li><b>Single responsibility</b> - this class only translates errors into responses; it never
 *       decides what is a valid business outcome.
 *   <li><b>Abstraction</b> - callers only need to know the problem detail shape, not which
 *       exception produced it.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** The URI prefix used for the problem {@code type} field. */
    public static final String PROBLEM_TYPE_BASE = "https://merqo.dev/problems/";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    /**
     * Creates the handler.
     *
     * @param clock the clock used for the failure timestamp
     */
    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /**
     * Handles the expected business failures.
     *
     * @param exception the raised business exception
     * @param request the current request
     * @return the problem detail response
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusinessException(
            BusinessException exception, HttpServletRequest request) {
        log.info(
                "Business rule rejected the request: code={} message={}",
                exception.getErrorCode(),
                exception.getMessage());
        HttpStatus status = HttpStatus.valueOf(exception.getHttpStatus());
        return ResponseEntity.status(status)
                .body(
                        ProblemDetail.of(
                                PROBLEM_TYPE_BASE + exception.getErrorCode().toLowerCase(),
                                status.getReasonPhrase(),
                                status.value(),
                                exception.getMessage(),
                                request.getRequestURI(),
                                exception.getErrorCode(),
                                currentCorrelationId(),
                                Instant.now(clock)));
    }

    /**
     * Handles failed bean validation on request bodies and query parameters.
     *
     * @param exception the raised validation exception
     * @param request the current request
     * @return the problem detail response with per field messages
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ProblemDetail problem =
                new ProblemDetail(
                        PROBLEM_TYPE_BASE + "validation-failed",
                        status.getReasonPhrase(),
                        status.value(),
                        "One or more fields are invalid.",
                        request.getRequestURI(),
                        "VALIDATION_FAILED",
                        currentCorrelationId(),
                        Instant.now(clock),
                        fieldErrors,
                        List.of());
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * Handles a missing or malformed request parameter.
     *
     * @param exception the raised exception
     * @param request the current request
     * @return the problem detail response
     */
    @ExceptionHandler({
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(
            Exception exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(
                        ProblemDetail.of(
                                PROBLEM_TYPE_BASE + "bad-request",
                                status.getReasonPhrase(),
                                status.value(),
                                exception.getMessage(),
                                request.getRequestURI(),
                                "BAD_REQUEST",
                                currentCorrelationId(),
                                Instant.now(clock)));
    }

    /**
     * Handles anything that was not expected. The detail stays generic so that internal information
     * never leaks to the browser.
     *
     * @param exception the raised exception
     * @param request the current request
     * @return the problem detail response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception while serving {}", request.getRequestURI(), exception);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .body(
                        ProblemDetail.of(
                                PROBLEM_TYPE_BASE + "internal-error",
                                status.getReasonPhrase(),
                                status.value(),
                                "An unexpected error occurred. The correlation id identifies this request in the logs.",
                                request.getRequestURI(),
                                "INTERNAL_ERROR",
                                currentCorrelationId(),
                                Instant.now(clock)));
    }

    private String currentCorrelationId() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId == null ? "unknown" : correlationId;
    }
}
