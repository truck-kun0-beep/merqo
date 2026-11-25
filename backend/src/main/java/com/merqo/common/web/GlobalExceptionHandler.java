package com.merqo.common.web;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ProblemDetail;
import com.merqo.common.error.PromotionConflictException;

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
     * Handles a promotion that fights the promotions that are already there.
     *
     * <p>The answer names every conflicting promotion with the variants they share and the SKUs of
     * those variants. The browser can therefore show the fight without a second call and without
     * loading the catalogue first. The status stays 409 and the code stays {@code
     * PROMOTION_CONFLICT}, so the caller reacts to the same code as before.
     *
     * @param exception the raised conflict
     * @param request the current request
     * @return the problem detail response with the conflicts
     */
    @ExceptionHandler(PromotionConflictException.class)
    public ResponseEntity<ProblemDetail> handlePromotionConflict(
            PromotionConflictException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        log.info(
                "Promotion conflict: code={} message={}",
                exception.getErrorCode(),
                exception.getMessage());
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (PromotionConflictException.Conflict conflict : exception.getConflicts()) {
            conflicts.add(conflict.toMap());
        }
        ProblemDetail problem =
                ProblemDetail.withConflicts(
                        PROBLEM_TYPE_BASE
                                + exception.getErrorCode().toLowerCase(java.util.Locale.ROOT),
                        status.getReasonPhrase(),
                        status.value(),
                        exception.getMessage(),
                        request.getRequestURI(),
                        exception.getErrorCode(),
                        currentCorrelationId(),
                        Instant.now(clock),
                        conflicts);
        return ResponseEntity.status(status).body(problem);
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
                        List.of(),
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
     * Handles a request body that is not valid JSON.
     *
     * <p>A broken body is the caller's mistake, not a server fault, so it is answered with the
     * status 400 and the code {@code BAD_REQUEST}. Without this handler the framework would let it
     * reach the catch all below and the caller would see a 500, which would wrongly suggest that
     * the server is broken.
     *
     * @param exception the raised exception
     * @param request the current request
     * @return the problem detail response
     */
    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.HttpRequestMethodNotSupportedException.class,
        org.springframework.web.HttpMediaTypeNotSupportedException.class
    })
    public ResponseEntity<ProblemDetail> handleUnreadableRequest(
            Exception exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(
                        ProblemDetail.of(
                                PROBLEM_TYPE_BASE + "bad-request",
                                status.getReasonPhrase(),
                                status.value(),
                                "The request could not be read. Check the JSON body and the HTTP method.",
                                request.getRequestURI(),
                                ErrorCodes.BAD_REQUEST,
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

    /**
     * Handles a request that Spring Security refused before it reached a controller.
     *
     * <p>That happens for an unauthenticated request, for a missing or wrong CSRF token and for a
     * request that is not allowed. The answer keeps the RFC 7807 shape, so the frontend always
     * reads the same fields.
     *
     * @param exception the raised exception
     * @param request the current request
     * @return the problem detail response
     */
    @ExceptionHandler({
        org.springframework.security.access.AccessDeniedException.class,
        org.springframework.security.web.csrf.CsrfException.class,
        org.springframework.security.core.AuthenticationException.class
    })
    public ResponseEntity<ProblemDetail> handleSecurity(
            Exception exception, HttpServletRequest request) {
        HttpStatus status;
        String errorCode;
        String detail;
        if (exception instanceof org.springframework.security.web.csrf.CsrfException) {
            status = HttpStatus.FORBIDDEN;
            errorCode = "CSRF_TOKEN_INVALID";
            detail = "The request was refused because the security token was missing or wrong.";
        } else if (exception instanceof org.springframework.security.access.AccessDeniedException) {
            status = HttpStatus.FORBIDDEN;
            errorCode = ErrorCodes.FORBIDDEN;
            detail = "You do not have the permission to do this.";
        } else {
            status = HttpStatus.UNAUTHORIZED;
            errorCode = ErrorCodes.UNAUTHENTICATED;
            detail = "You must sign in to continue.";
        }
        log.info("Spring Security refused the request: code={}", errorCode);
        return ResponseEntity.status(status)
                .body(
                        ProblemDetail.of(
                                PROBLEM_TYPE_BASE + errorCode.toLowerCase(java.util.Locale.ROOT),
                                status.getReasonPhrase(),
                                status.value(),
                                detail,
                                request.getRequestURI(),
                                errorCode,
                                currentCorrelationId(),
                                Instant.now(clock)));
    }

    private String currentCorrelationId() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId == null ? "unknown" : correlationId;
    }
}
