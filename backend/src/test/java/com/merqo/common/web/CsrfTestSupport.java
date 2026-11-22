package com.merqo.common.web;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The CSRF token for the integration tests of Merqo.
 *
 * <p><b>Why this class exists instead of the helper of Spring Security.</b> The helper of Spring
 * Security, {@code SecurityMockMvcRequestPostProcessors.csrf()}, reaches into the real {@code
 * CsrfFilter} of the running application and <b>replaces its token repository</b> with a session
 * repository. That repository writes the token in the session and never in the {@code XSRF-TOKEN}
 * cookie. The replacement is permanent: the filter is a bean of the shared application context, so
 * every later request of every later test in the same context keeps the session repository.
 *
 * <p>The result is a test that passes on its own and fails inside a suite: the cookie the
 * application really writes is gone, and only the first test to use the Spring helper causes it.
 * That is a broken test, not a broken application, but a broken test is still a broken test.
 *
 * <p>This helper does the same thing the browser does, and nothing more:
 *
 * <ol>
 *   <li>It reuses the {@code XSRF-TOKEN} cookie when the request already carries one.
 *   <li>Otherwise it makes a token and puts it in the cookie.
 *   <li>It sends the token back in the {@code X-XSRF-TOKEN} header.
 * </ol>
 *
 * <p>The real filter then loads the token from the cookie and compares it with the header, which is
 * exactly the comparison the browser goes through. The application code is untouched.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - the class is a {@link RequestPostProcessor}.
 *   <li><b>Single responsibility</b> - it only prepares the token of one request.
 *   <li><b>Composition</b> - it reuses the cookie and the header names of the real application, so
 *       one place has to change when they change.
 * </ul>
 */
public final class CsrfTestSupport {

    /** The name of the cookie that holds the CSRF token. */
    public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";

    /** The header the CSRF token has to be sent back in. */
    public static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    private CsrfTestSupport() {
        // This class only holds one static method.
    }

    /**
     * Adds a valid CSRF token to the request.
     *
     * @return the request post processor
     */
    public static RequestPostProcessor csrf() {
        return request -> {
            String token = tokenOf(request.getCookies());
            if (token == null) {
                token = UUID.randomUUID().toString();
                request.setCookies(withCsrfCookie(request.getCookies(), token));
            }
            request.addHeader(CSRF_HEADER_NAME, token);
            return request;
        };
    }

    /**
     * The token of the cookies, or {@code null} when they carry none.
     *
     * @param cookies the cookies of the request, may be {@code null}
     * @return the token, or {@code null}
     */
    private static String tokenOf(Cookie[] cookies) {
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (CSRF_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * The same cookies with the CSRF cookie added.
     *
     * @param cookies the cookies of the request, may be {@code null}
     * @param token the value of the CSRF cookie
     * @return the new set of cookies
     */
    private static Cookie[] withCsrfCookie(Cookie[] cookies, String token) {
        List<Cookie> all = new ArrayList<>();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (!CSRF_COOKIE_NAME.equals(cookie.getName())) {
                    all.add(cookie);
                }
            }
        }
        Cookie csrfCookie = new Cookie(CSRF_COOKIE_NAME, token);
        csrfCookie.setPath("/");
        all.add(csrfCookie);
        return all.toArray(new Cookie[0]);
    }

    /**
     * Adds a CSRF token to the request and keeps the cookie it carried.
     *
     * @param request the request
     * @return the request
     */
    public static MockHttpServletRequest withCsrf(MockHttpServletRequest request) {
        csrf().postProcessRequest(request);
        return request;
    }
}
