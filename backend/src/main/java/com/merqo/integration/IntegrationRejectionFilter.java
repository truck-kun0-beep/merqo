package com.merqo.integration;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.merqo.common.error.ErrorCodes;
import com.merqo.common.web.ProblemResponseWriter;

/**
 * Rejects requests carrying integration API-key headers when sent to staff {@code /api/v1/**}
 * endpoints, keeping the session auth surface strictly separated from webhook authentication.
 */
public class IntegrationRejectionFilter extends OncePerRequestFilter {

    private final ProblemResponseWriter problemWriter;

    public IntegrationRejectionFilter(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (hasHeader(request, IntegrationAuthFilter.HEADER_KEY)
                || hasHeader(request, IntegrationAuthFilter.HEADER_SIGNATURE)) {
            problemWriter.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCodes.UNAUTHENTICATED,
                    "Integration API credentials are not accepted on staff endpoints.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean hasHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value != null && !value.isBlank();
    }
}
