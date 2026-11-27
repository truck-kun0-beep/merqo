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
 * <p><b>The conflicts field.</b> Most failures carry no extra information and the field is empty. A
 * promotion conflict is the one failure that has to name what it fights, so the conflicts are part
 * of the answer and the browser can show them without a second call.
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
 * @param conflicts the records that caused the failure, empty when not applicable
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
        List<String> errors,
        List<Map<String, Object>> conflicts) {

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
                List.of(),
                List.of());
    }

    /**
     * Builds a problem detail for a failure that names the records in the way.
     *
     * @param type the problem type URI
     * @param title the short summary
     * @param status the HTTP status
     * @param detail the explanation
     * @param instance the request URI
     * @param errorCode the stable error code
     * @param correlationId the request correlation id
     * @param timestamp when the failure happened
     * @param conflicts the records in the way
     * @return the problem detail
     */
    public static ProblemDetail withConflicts(
            String type,
            String title,
            int status,
            String detail,
            String instance,
            String errorCode,
            String correlationId,
            Instant timestamp,
            List<Map<String, Object>> conflicts) {
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
                List.of(),
                List.copyOf(conflicts));
    }
}
