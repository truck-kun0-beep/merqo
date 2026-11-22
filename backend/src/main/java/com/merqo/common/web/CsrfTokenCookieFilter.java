package com.merqo.common.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes sure the browser always has the CSRF token before it sends its first request that changes
 * something.
 *
 * <p>Responsibility: read the token that the {@code CsrfFilter} put on the request and ask for its
 * value. Asking for the value is what makes the token real: the {@code CookieCsrfTokenRepository}
 * only writes the {@code XSRF-TOKEN} cookie when the value is actually read.
 *
 * <p>Without this filter the cookie would only appear on the first request that changes something,
 * and that first request would be refused. The user would have to click twice.
 *
 * <p>The token is not a secret. It only proves that the request came from this site and not from
 * another one, which is why it may travel in a cookie that JavaScript can read.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - the class extends Spring's {@link OncePerRequestFilter}.
 *   <li><b>Single responsibility</b> - the class only makes sure the token exists.
 *   <li><b>Composition</b> - the filter is placed next to the CSRF filter instead of changing it.
 * </ul>
 */
public class CsrfTokenCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken token = tokenOf(request);
        if (token != null) {
            // Reading the value is what writes the cookie.
            token.getToken();
        }
        filterChain.doFilter(request, response);
    }

    /**
     * The CSRF token of the request, whatever attribute name the framework used for it.
     *
     * <p><b>Why the lookup is not a single constant.</b> The CSRF filter publishes the token as a
     * request attribute, but the name of that attribute changed between framework versions, and the
     * request handler wraps it in a supplier instead of the token itself. The filter therefore asks
     * the request for the attribute under every name it knows, and unwraps the supplier when it
     * finds one. When no token is found the filter simply does nothing, and the CSRF filter keeps
     * its own behaviour.
     *
     * @param request the current request
     * @return the token, or {@code null} when the request carries none
     */
    private CsrfToken tokenOf(HttpServletRequest request) {
        for (String name :
                new String[] {
                    CsrfToken.class.getName(),
                    "_csrf",
                    CsrfToken.class.getSimpleName(),
                    "org.springframework.security.web.csrf.CsrfToken"
                }) {
            Object attribute = request.getAttribute(name);
            CsrfToken token = unwrap(attribute);
            if (token != null) {
                return token;
            }
        }
        return null;
    }

    /**
     * Unwraps the value of an attribute into a token.
     *
     * @param attribute the value of the attribute
     * @return the token, or {@code null} when the value is not a token
     */
    private CsrfToken unwrap(Object attribute) {
        if (attribute instanceof CsrfToken token) {
            return token;
        }
        if (attribute instanceof java.util.function.Supplier<?> supplier) {
            Object value = supplier.get();
            if (value instanceof CsrfToken token) {
                return token;
            }
        }
        return null;
    }
}
