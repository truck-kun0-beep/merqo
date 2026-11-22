package com.merqo.common.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import com.merqo.common.error.ErrorCodes;

/**
 * Answers a request that was refused before it reached a controller.
 *
 * <p>Responsibility: turn the framework's refusal into the same RFC 7807 problem detail as every
 * other failure. The status is always 403 and the code says why:
 *
 * <ul>
 *   <li>{@code CSRF_TOKEN_INVALID} when the security token was missing or wrong.
 *   <li>{@code FORBIDDEN} when the caller is signed in but not allowed.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - the class implements Spring Security's {@link
 *       AccessDeniedHandler}.
 *   <li><b>Single responsibility</b> - one answer, one place.
 *   <li><b>Polymorphism</b> - the code is chosen from the type of the exception that was raised.
 *   <li><b>Composition</b> - the answer itself is written by {@link ProblemResponseWriter}, which
 *       the whole security layer shares.
 * </ul>
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problemWriter;

    /**
     * Creates the handler.
     *
     * @param problemWriter the writer of the error answer
     */
    public ProblemAccessDeniedHandler(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void handle(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        boolean csrfFailure = accessDeniedException instanceof CsrfException;
        String errorCode = csrfFailure ? ErrorCodes.CSRF_TOKEN_INVALID : ErrorCodes.FORBIDDEN;
        String detail =
                csrfFailure
                        ? "The request was refused because the security token was missing or wrong."
                        : "You do not have the permission to do this.";

        problemWriter.write(request, response, HttpStatus.FORBIDDEN, errorCode, detail);
    }
}
