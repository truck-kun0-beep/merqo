package com.merqo.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.merqo.user.AppUserRepository;

/**
 * The integration test of the CSRF token after a sign in.
 *
 * <p>It reproduces the bug that made the live site fail: the sign in worked, but every request that
 * changes something was answered with a 403, so the user could not sign out and could not create an
 * account.
 *
 * <p><b>The rule the browser follows.</b> The browser keeps two cookies: the session cookie and the
 * readable CSRF cookie. When it sends a request that changes something, it copies the value of the
 * CSRF cookie into the {@code X-XSRF-TOKEN} header. The backend compares the two.
 *
 * <p><b>What the test does.</b> It signs in and then uses <b>only the cookies the sign in answer
 * returned</b>. It never asks for a fresh token first, because the browser does not do that either:
 * the browser already has the token it read from the cookie. If the sign in answer does not carry a
 * usable CSRF cookie, the very next request that changes something fails, and that is the bug.
 *
 * <p><b>Why this is a real test and not a mock.</b> The whole chain runs: the real filter chain, the
 * real session in PostgreSQL and the real CSRF filter of Spring Security. The cookies are the ones
 * the application really wrote.
 *
 * <p><b>Why the accounts are removed again.</b> The database is shared with the other suites, and one
 * of them asserts the exact number of accounts. The account of this test is therefore deleted again
 * after every test.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the cookie names live in this class, so a change of the application
 *       is made in one place.
 *   <li><b>Single responsibility</b> - the class only proves the CSRF rule of a signed in browser.
 *   <li><b>Composition</b> - the test drives the public API, so it proves the behaviour of the
 *       whole chain rather than of one class.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfAfterLoginIntegrationTest {

    /** The name of the cookie that holds the session id. */
    private static final String SESSION_COOKIE = "MERQO_SESSION";

    /** The name of the cookie that holds the CSRF token. */
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    /** The header the CSRF token has to be sent back in. */
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired private MockMvc mockMvc;

    @Autowired private AppUserRepository userRepository;

    /** The names of the accounts this test wrote, so they can be removed again. */
    private final List<String> writtenAccounts = new ArrayList<>();

    @AfterEach
    void removeTheAccountsOfThisTest() {
        // The database is shared with the other suites and one of them asserts the exact number of
        // accounts, so the accounts of this test are removed again.
        for (String username : writtenAccounts) {
            userRepository.findByUsernameIgnoreCase(username).ifPresent(userRepository::delete);
        }
        writtenAccounts.clear();
    }

    @Test
    @DisplayName("the sign in answer hands out a CSRF cookie the next request may use")
    void theSignInAnswerHandsOutACsrfCookieTheNextRequestMayUse() throws Exception {
        MvcResult login = demoLogin();

        Cookie csrf = cookieNamed(login, CSRF_COOKIE);
        assertNotNull(
                csrf,
                "The sign in answer must carry the XSRF-TOKEN cookie, otherwise the browser has no"
                        + " token to send back and every request that changes something is refused"
                        + " with a 403");
        assertTrue(
                !csrf.getValue().isBlank(),
                "The CSRF cookie must carry a value, otherwise the browser sends an empty header");
    }

    @Test
    @DisplayName("the sign out works with only the cookies the sign in answer returned")
    void theSignOutWorksWithOnlyTheCookiesTheSignInAnswerReturned() throws Exception {
        MvcResult login = demoLogin();

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(cookiesOf(login))
                                .header(CSRF_HEADER, csrfValueOf(login)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("creating an account works with only the cookies the sign in answer returned")
    void creatingAnAccountWorksWithOnlyTheCookiesTheSignInAnswerReturned() throws Exception {
        MvcResult login = demoLogin();

        String username = username();
        writtenAccounts.add(username);

        mockMvc.perform(
                        post("/api/v1/users/create")
                                .cookie(cookiesOf(login))
                                .header(CSRF_HEADER, csrfValueOf(login))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\""
                                                + username
                                                + "\",\"fullName\":\"CSRF Probe\","
                                                + "\"accountType\":\"STAFF\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("the account of the probe is written exactly once")
    void theAccountOfTheProbeIsWrittenExactlyOnce() throws Exception {
        long before = userRepository.count();

        MvcResult login = demoLogin();
        String username = username();
        writtenAccounts.add(username);

        mockMvc.perform(
                        post("/api/v1/users/create")
                                .cookie(cookiesOf(login))
                                .header(CSRF_HEADER, csrfValueOf(login))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\""
                                                + username
                                                + "\",\"fullName\":\"CSRF Probe\","
                                                + "\"accountType\":\"STAFF\"}"))
                .andExpect(status().isCreated());

        assertEquals(before + 1, userRepository.count(), "Exactly one account may be written");
    }

    /**
     * Signs the owner in and returns the answer of the sign in.
     *
     * <p>The sign in itself needs a token, so the test asks for one the way the browser does: it
     * reads the cookie of a plain request and sends the value back in the header. The bug is not in
     * the sign in, it is in what happens <b>after</b> it.
     *
     * @return the answer of the sign in
     * @throws Exception when the sign in fails
     */
    private MvcResult demoLogin() throws Exception {
        MvcResult status =
                mockMvc.perform(get("/api/v1/auth/status")).andExpect(status().isOk()).andReturn();
        Cookie csrf = cookieNamed(status, CSRF_COOKIE);
        assertNotNull(csrf, "The status answer must hand out the CSRF cookie");

        return mockMvc.perform(
                        post("/api/v1/demo/login")
                                .cookie(csrf)
                                .header(CSRF_HEADER, csrf.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"accountKey\":\"owner\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    /**
     * The value of the CSRF cookie of an answer, or an empty string when there is none.
     *
     * @param result the answer
     * @return the value
     */
    private String csrfValueOf(MvcResult result) {
        Cookie cookie = cookieNamed(result, CSRF_COOKIE);
        return cookie == null ? "" : cookie.getValue();
    }

    /**
     * The cookie of an answer with a given name, or {@code null} when the answer carries none.
     *
     * @param result the answer
     * @param name the name of the cookie
     * @return the cookie, or {@code null}
     */
    private Cookie cookieNamed(MvcResult result, String name) {
        Cookie[] cookies = result.getResponse().getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    /**
     * Every cookie of an answer, so the next request travels with exactly what the browser has.
     *
     * @param result the answer
     * @return the cookies
     */
    private Cookie[] cookiesOf(MvcResult result) {
        Cookie[] cookies = result.getResponse().getCookies();
        return cookies == null ? new Cookie[0] : cookies;
    }

    /**
     * A login name that no other test uses, so the accounts of the suite never fight each other.
     *
     * @return the login name
     */
    private String username() {
        String raw = "csrfprobe" + System.nanoTime();
        return raw.length() > 40 ? raw.substring(0, 40) : raw.toLowerCase(Locale.ROOT);
    }
}
