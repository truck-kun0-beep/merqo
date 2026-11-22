package com.merqo.user;

import java.util.List;

/**
 * The rule that keeps a user who still has to change the password inside the sign in screens.
 *
 * <p>Responsibility: answer one question: "may this request go through while a password change is
 * still pending?". The list of allowed paths lives here, so the filter and the services can never
 * disagree about what a pending change allows.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the class only knows the rule, not how a request is read.
 *   <li><b>Immutability</b> - the allowed paths are a constant list and are never changed at
 *       runtime.
 * </ul>
 */
public final class PasswordChangePolicy {

    /**
     * The paths that stay open while a password change is pending.
     *
     * <p>They are exactly the calls a user needs to read the session and to fix the password. Every
     * other endpoint is refused until the password has been changed.
     */
    public static final List<String> ALLOWED_PATHS =
            List.of(
                    "/api/v1/auth/status",
                    "/api/v1/auth/login",
                    "/api/v1/auth/me",
                    "/api/v1/auth/change-password",
                    "/api/v1/auth/logout",
                    "/api/v1/demo/accounts",
                    "/api/v1/demo/login",
                    "/api/v1/health",
                    "/api/v1/health/ping");

    private PasswordChangePolicy() {
        // This class only holds the rule.
    }

    /**
     * Whether a request may go through while a password change is pending.
     *
     * @param requestPath the path of the request, without the query string
     * @param mustChangePassword whether the caller still has to change the password
     * @return {@code true} when the request may go through
     */
    public static boolean mayProceed(String requestPath, boolean mustChangePassword) {
        if (!mustChangePassword) {
            return true;
        }
        return isAllowedPath(requestPath);
    }

    /**
     * Whether a path is on the allowed list.
     *
     * @param requestPath the path of the request
     * @return {@code true} when the path stays open
     */
    public static boolean isAllowedPath(String requestPath) {
        if (requestPath == null) {
            return false;
        }
        for (String allowed : ALLOWED_PATHS) {
            if (requestPath.equals(allowed) || requestPath.startsWith(allowed + "/")) {
                return true;
            }
        }
        return false;
    }
}
