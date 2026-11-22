package com.merqo.common.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.merqo.common.error.ErrorCodes;

/**
 * Answers a request that reached the API without a valid session.
 *
 * <p>Responsibility: turn the framework's refusal into the same RFC 7807 problem detail as every
 * other failure, with the status 401 and the code {@code UNAUTHENTICATED}. The frontend then knows
 * that it has to send the user to the login page.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - the class implements Spring Security's {@link
 *       AuthenticationEntryPoint}.
 *   <li><b>Single responsibility</b> - one answer, one place.
 *   <li><b>Composition</b> - the answer itself is written by {@link ProblemResponseWriter}, which
 *       the whole security layer shares.
 * </ul>
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponseWriter problemWriter;

    /**
     * Creates the entry point.
     *
     * @param problemWriter the writer of the error answer
     */
    public ProblemAuthenticationEntryPoint(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        problemWriter.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.UNAUTHENTICATED,
                "You must sign in to continue.");
    }
}
