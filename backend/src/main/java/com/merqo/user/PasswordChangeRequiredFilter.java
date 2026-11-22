package com.merqo.user;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.merqo.common.error.ErrorCodes;
import com.merqo.common.web.ProblemResponseWriter;

/**
 * Keeps a user who still has to change the password inside the sign in screens.
 *
 * <p>Responsibility: refuse every business request of an account whose password must be changed,
 * until the password has been changed. The refusal carries the stable code {@code
 * PASSWORD_CHANGE_REQUIRED}, so the frontend sends the user to the change password screen instead
 * of showing an error.
 *
 * <p>The filter sits behind the authorization filter, so an anonymous caller is already answered
 * with 401 and never reaches this rule. Only a signed in account with a pending change is affected.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Template method</b> - {@link OncePerRequestFilter} decides when the filter runs; this
 *       class only fills in the request specific part.
 *   <li><b>Strategy</b> - the rule itself lives in {@link PasswordChangePolicy}, so it can be
 *       tested without a servlet.
 *   <li><b>Composition</b> - the filter shares the answer writer with the rest of the security
 *       layer, so every refusal has the same shape.
 * </ul>
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeRequiredFilter.class);

    private final ProblemResponseWriter problemWriter;

    /**
     * Creates the filter.
     *
     * @param problemWriter the writer of the error answer
     */
    public PasswordChangeRequiredFilter(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isChangePending() && !PasswordChangePolicy.isAllowedPath(request.getRequestURI())) {
            log.info(
                    "A request was refused because the password of the account must be changed: {}",
                    request.getRequestURI());
            problemWriter.write(
                    request,
                    response,
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.PASSWORD_CHANGE_REQUIRED,
                    "You must change your password before you can continue.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isChangePending() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (!(authentication.getPrincipal() instanceof MerqoUserPrincipal principal)) {
            return false;
        }
        return principal.mustChangePassword();
    }
}
