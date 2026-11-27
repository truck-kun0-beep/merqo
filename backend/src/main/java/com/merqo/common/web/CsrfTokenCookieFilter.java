package com.merqo.common.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes sure the browser always has a fresh CSRF token, on every answer and not only on the first
 * one.
 *
 * <p>Responsibility: read the token that the {@code CsrfFilter} put on the request, ask for its
 * value, and make sure the {@code XSRF-TOKEN} cookie is part of the answer.
 *
 * <p><b>Why the cookie has to be on every answer.</b> The token of Spring Security is
 * <b>deferred</b>: the value only exists once something asks for it. The cookie repository writes
 * the cookie when it generates a token, so a request that already carried a cookie got no new one
 * in its answer. A sign in therefore answered with the session cookie alone.
 *
 * <p>That is exactly the bug that broke the live site. The browser sent the next request with the
 * token it had, but the answer to the sign in had changed the session, and the token of the answer
 * no longer belonged to the session the browser was in. Every request that changed something was
 * then refused with a 403: the sign out and the creation of an account failed.
 *
 * <p>The rule now is simple: <b>every answer carries the token of that answer.</b> The browser
 * always holds a token that belongs to the session it is in, so the next request that changes
 * something can send it back and is accepted.
 *
 * <p>The token is not a secret. It only proves that the request came from this site and not from
 * another one, which is why it may travel in a cookie that JavaScript can read.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - the class extends Spring's {@link OncePerRequestFilter}.
 *   <li><b>Single responsibility</b> - the class only makes sure the token cookie exists.
 *   <li><b>Delegation</b> - the cookie itself is written by the repository, so the name, the path
 *       and the flags of the cookie live in one place.
 *   <li><b>Composition</b> - the filter is placed next to the CSRF filter instead of changing it.
 * </ul>
 */
public class CsrfTokenCookieFilter extends OncePerRequestFilter {

    /** The name of the cookie that holds the CSRF token. */
    public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";

    private final CsrfTokenRepository tokenRepository;

    /**
     * Creates the filter.
     *
     * @param tokenRepository the repository that holds the token
     */
    public CsrfTokenCookieFilter(CsrfTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken token = tokenOf(request);
        if (token != null) {
            // Reading the value is what makes the deferred token real.
            token.getToken();
            writeCookieIfMissing(request, response, token);
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Writes the token cookie, unless the answer already carries one.
     *
     * <p>The repository writes the cookie, so the name, the path and the flags of the cookie are
     * the ones of the application and not a second copy of them.
     *
     * @param request the current request
     * @param response the answer
     * @param token the token of the request
     */
    private void writeCookieIfMissing(
            HttpServletRequest request, HttpServletResponse response, CsrfToken token) {
        if (alreadyWritten(response)) {
            return;
        }
        tokenRepository.saveToken(token, request, response);
    }

    /**
     * Whether the answer already carries the token cookie.
     *
     * <p>The answer may carry the session cookie and the token cookie at the same time, and each of
     * them has to keep its own {@code Set-Cookie} header. A proxy that joins those headers would
     * destroy both of them.
     *
     * @param response the answer
     * @return {@code true} when the answer already has the cookie
     */
    private boolean alreadyWritten(HttpServletResponse response) {
        for (String header : response.getHeaders(HttpHeaders.SET_COOKIE)) {
            if (header.startsWith(CSRF_COOKIE_NAME + "=")) {
                return true;
            }
        }
        return false;
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
