package com.merqo.common.error;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error shape every Merqo API endpoint returns.
 *
 * <p>Responsibility: describe a failure in a format that follows RFC 7807 ("problem details") so
 * the frontend can render any error without special cases.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Immutability / value object</b> - a {@code record} with only immutable components; it
 *       cannot be changed after it is created.
 *   <li><b>Composition</b> - the problem holds smaller objects (the field errors and the extra
 *       details) instead of flattening everything.
 * </ul>
 *
 * @param type a URI reference identifying the problem type
 * @param title a short human readable summary
 * @param status the HTTP status code
 * @param detail a human readable explanation of this occurrence
 * @param instance the URI of the request that failed
 * @param errorCode the stable machine readable code clients can branch on
 * @param correlationId the id of this request, also written to the logs
 * @param timestamp when the failure happened
 * @param fieldErrors per field validation failures, empty when not applicable
 * @param errors additional stable codes, empty when not applicable
 */
public record ProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String errorCode,
        String correlationId,
        Instant timestamp,
        Map<String, String> fieldErrors,
        List<String> errors) {

    /**
     * Builds a problem detail for a failure without field level information.
     *
     * @param type the problem type URI
     * @param title the short summary
     * @param status the HTTP status
     * @param detail the explanation
     * @param instance the request URI
     * @param errorCode the stable error code
     * @param correlationId the request correlation id
     * @param timestamp when the failure happened
     * @return the problem detail
     */
    public static ProblemDetail of(
            String type,
            String title,
            int status,
            String detail,
            String instance,
            String errorCode,
            String correlationId,
            Instant timestamp) {
        return new ProblemDetail(
                type,
                title,
                status,
                detail,
                instance,
                errorCode,
                correlationId,
                timestamp,
                Map.of(),
                List.of());
    }
}
