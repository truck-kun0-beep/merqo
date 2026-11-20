package com.merqo.common.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id and puts it into the logs.
 *
 * <p>Responsibility: make it possible to find all log lines that belong to one request, including
 * the ones written by other modules.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Template method pattern</b> - {@link OncePerRequestFilter} decides when the filter runs;
 *       this class only fills in the request specific part.
 *   <li><b>Composition</b> - the filter works together with the logging configuration rather than
 *       replacing it.
 * </ul>
 *
 * <p>The id is read from the {@code X-Correlation-Id} header when a caller supplies one, so a
 * request can be traced across the frontend, the Cloudflare proxy and the backend. Otherwise a new
 * id is generated.
 *
 * <p>The filter is registered by {@link CorrelationIdFilterConfiguration} so that its order and URL
 * pattern are explicit.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** The header the correlation id travels in. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** The key used inside the logging context. */
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            jakarta.servlet.FilterChain filterChain)
            throws jakarta.servlet.ServletException, java.io.IOException {

        String correlationId = resolveCorrelationId(request);
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String supplied = request.getHeader(CORRELATION_ID_HEADER);
        if (supplied != null && !supplied.isBlank() && supplied.length() <= 100) {
            return supplied.trim();
        }
        return UUID.randomUUID().toString();
    }
}
