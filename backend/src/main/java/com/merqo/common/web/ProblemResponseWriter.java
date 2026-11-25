package com.merqo.common.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Writes the one error shape of Merqo into a response.
 *
 * <p>Responsibility: build the RFC 7807 problem detail once, so that every place that answers a
 * request before a controller is reached sends exactly the same shape. The frontend then reads the
 * same fields whichever layer refused the request.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - this class only writes the answer; it never decides which
 *       answer is right.
 *   <li><b>Composition</b> - the three users of this class (the entry point, the access denied
 *       handler and the password change filter) share one writer instead of repeating the same
 *       twenty lines.
 * </ul>
 */
@Component
public class ProblemResponseWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Creates the writer.
     *
     * @param objectMapper the JSON writer
     * @param clock the clock used for the failure timestamp
     */
    public ProblemResponseWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Writes one problem detail into the response.
     *
     * @param request the current request
     * @param response the answer
     * @param status the HTTP status
     * @param errorCode the stable error code
     * @param detail the human readable explanation
     * @throws IOException when the answer cannot be written
     */
    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String errorCode,
            String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");

        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("errorCode", errorCode);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        problem.put("correlationId", correlationId == null ? "unknown" : correlationId);
        problem.put("timestamp", Instant.now(clock).toString());
        problem.put("fieldErrors", Map.of());
        problem.put("errors", List.of());
        problem.put("conflicts", List.of());

        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
