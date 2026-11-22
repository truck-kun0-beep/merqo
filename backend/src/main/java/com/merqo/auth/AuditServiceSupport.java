package com.merqo.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A tiny helper for the controllers that need the address of the caller.
 *
 * <p>Responsibility: read the forwarded header the Cloudflare Function sets.
 *
 * <p>The value can be spoofed by anybody who calls the Render URL directly. That is written down in
 * ASSUMPTIONS.md.
 *
 * <p>OOP concept: a static helper keeps one small piece of logic in one place, instead of copying
 * it into every controller.
 */
final class AuditServiceSupport {

    private AuditServiceSupport() {
        // This class only holds one static method.
    }

    /**
     * The address the request came from.
     *
     * @param request the current request
     * @return the address, or {@code null}
     */
    static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
