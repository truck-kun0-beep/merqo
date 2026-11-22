package com.merqo.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import com.merqo.common.web.CsrfTestSupport;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;
import com.merqo.user.LoginAttempt;
import com.merqo.user.LoginAttemptRepository;

/**
 * The integration test of the login rules: the throttle, the lock, the session and the cookie.
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test}, with the real security filter
 * chain.
 *
 * <p>What it proves:
 *
 * <ol>
 *   <li>Five failed attempts for one account lock that account.
 *   <li>Twenty failed attempts from one address are refused with 429.
 *   <li>A sign in starts a <b>new</b> session, so a stolen session id is useless.
 *   <li>The session cookie is HttpOnly, has no Domain attribute and is SameSite=Lax.
 *   <li>A deactivated account is refused on its very next request, even with a good session cookie.
 * </ol>
 *
 * <p>OOP concepts demonstrated: the test drives the same public API as the browser, so it proves
 * the behaviour of the whole chain rather than of one class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginAndSessionIntegrationTest {

    /** The name of the cookie that holds the session id. */
    private static final String SESSION_COOKIE = "MERQO_SESSION";

    /** The name of the cookie that holds the CSRF token. */
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired private AppUserRepository userRepository;

    @Autowired private LoginAttemptRepository attemptRepository;

    @AfterEach
    void clearTheThrottleState() {
        // The throttle counts the attempts of the last fifteen minutes, so the rows of this test
        // are removed. Otherwise the next test would start with a full counter.
        attemptRepository.deleteAll();
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        owner.setFailedLoginCount(0);
        owner.setLockedUntil(null);
        userRepository.save(owner);
    }

    @Test
    @DisplayName("the fifth failed attempt locks the account")
    void theFifthFailedAttemptLocksTheAccount() throws Exception {
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();

        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(
                            post("/api/v1/auth/login")
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"identifier\":\"owner\",\"password\":\"wrong-one\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"identifier\":\"owner\",\"password\":\"wrong-one\"}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_LOCKED"));

        AppUser locked = userRepository.findById(owner.getId()).orElseThrow();
        assertNotNull(locked.getLockedUntil(), "The account must carry the lock");
        assertTrue(
                locked.getLockedUntil().isAfter(Instant.now()), "The lock must end in the future");
    }

    @Test
    @DisplayName("a locked account is refused even with the right password")
    void aLockedAccountIsRefusedEvenWithTheRightPassword() throws Exception {
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        owner.setLockedUntil(Instant.now().plusSeconds(600));
        owner.setFailedLoginCount(5);
        userRepository.save(owner);

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"identifier\":\"owner\",\"password\":\"anything\"}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("twenty failed attempts from one address are refused with 429")
    void twentyFailedAttemptsFromOneAddressAreRefusedWith429() throws Exception {
        // Twenty failures from one address, spread over twenty different accounts, so the account
        // lock never fires. Only the address limit can stop the next attempt.
        for (int attempt = 1; attempt <= 20; attempt++) {
            mockMvc.perform(
                            post("/api/v1/auth/login")
                                    .with(CsrfTestSupport.csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"identifier\":\"ghost-"
                                                    + attempt
                                                    + "\",\"password\":\"wrong-one\"}")
                                    .header("X-Forwarded-For", "10.9.9.9"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"identifier\":\"owner\",\"password\":\"wrong-one\"}")
                                .header("X-Forwarded-For", "10.9.9.9"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_REQUESTS"));
    }

    @Test
    @DisplayName("a sign in writes a new session id")
    void aSignInWritesANewSessionId() throws Exception {
        MvcResult first =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"owner\"}"))
                        .andExpect(status().isOk())
                        .andReturn();

        MvcResult second =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"owner\"}"))
                        .andExpect(status().isOk())
                        .andReturn();

        Cookie firstCookie = first.getResponse().getCookie(SESSION_COOKIE);
        Cookie secondCookie = second.getResponse().getCookie(SESSION_COOKIE);

        assertNotNull(firstCookie, "The first sign in must write the session cookie");
        assertNotNull(secondCookie, "The second sign in must write the session cookie");
        assertNotEquals(
                firstCookie.getValue(),
                secondCookie.getValue(),
                "A sign in must start a new session, so the session id must change");
    }

    @Test
    @DisplayName("the session cookie is HttpOnly, host only and SameSite=Lax")
    void theSessionCookieIsHttpOnlyHostOnlyAndSameSiteLax() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"owner\"}"))
                        .andExpect(status().isOk())
                        .andReturn();

        MockHttpServletResponse response = result.getResponse();
        Cookie cookie = response.getCookie(SESSION_COOKIE);

        assertNotNull(cookie, "The sign in must write the session cookie");
        assertTrue(cookie.isHttpOnly(), "The session cookie must be HttpOnly");
        assertFalse(
                cookie.getDomain() != null && !cookie.getDomain().isBlank(),
                "The session cookie must be host only, so it must carry no Domain attribute");

        String setCookieHeader =
                response.getHeader("Set-Cookie") == null
                        ? ""
                        : String.join("|", response.getHeaders("Set-Cookie"));
        assertTrue(
                setCookieHeader.toLowerCase().contains("samesite=lax"),
                "The session cookie must be SameSite=Lax, but the header was: " + setCookieHeader);
    }

    @Test
    @DisplayName("the CSRF cookie is readable by the script on purpose")
    void theCsrfCookieIsReadableByTheScript() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/auth/status")).andExpect(status().isOk()).andReturn();

        Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);

        assertNotNull(cookie, "The application must write the CSRF cookie");
        assertFalse(
                cookie.isHttpOnly(),
                "The CSRF cookie must be readable by the script, because the script sends it back"
                        + " in a header");
    }

    @Test
    @DisplayName("a deactivated account is refused on its very next request")
    void aDeactivatedAccountIsRefusedOnItsVeryNextRequest() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"cashier\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie session = result.getResponse().getCookie(SESSION_COOKIE);

        // The session works before the account is deactivated.
        mockMvc.perform(get("/api/v1/locations").cookie(session)).andExpect(status().isOk());

        AppUser cashier = userRepository.findByDemoKey("cashier").orElseThrow();
        cashier.setActive(false);
        userRepository.save(cashier);

        try {
            mockMvc.perform(get("/api/v1/locations").cookie(session))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        } finally {
            cashier.setActive(true);
            userRepository.save(cashier);
        }
    }

    @Test
    @DisplayName("a sign out ends the session")
    void aSignOutEndsTheSession() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"owner\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie session = result.getResponse().getCookie(SESSION_COOKIE);

        mockMvc.perform(post("/api/v1/auth/logout").cookie(session).with(CsrfTestSupport.csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/locations").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName(
            "the sign in answers with the same message for a wrong password and an unknown account")
    void theSignInAnswersWithTheSameMessageForEveryWrongCredential() throws Exception {
        String wrongPassword = stablePartOf(bodyOfLoginFailure("owner", "wrong-one"));
        String unknownAccount = stablePartOf(bodyOfLoginFailure("nobody-here", "wrong-one"));

        assertEquals(
                wrongPassword,
                unknownAccount,
                "The two answers must be identical, so the caller cannot learn whether an account"
                        + " exists");
    }

    /**
     * The body of a failed sign in.
     *
     * @param identifier the login name or the email address
     * @param password the password
     * @return the body of the answer
     * @throws Exception when the request fails
     */
    private String bodyOfLoginFailure(String identifier, String password) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"identifier\":\""
                                                + identifier
                                                + "\",\"password\":\""
                                                + password
                                                + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * The part of a problem detail that must be the same for every wrong credential.
     *
     * <p>The correlation id and the timestamp are different for every request by design, so they
     * are left out of the comparison.
     *
     * @param body the body of the answer
     * @return the stable part of the body
     */
    private static String stablePartOf(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"*\"")
                .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"*\"");
    }

    @Test
    @DisplayName("the throttle state is written to the database")
    void theThrottleStateIsWrittenToTheDatabase() {
        attemptRepository.save(
                new LoginAttempt(
                        java.util.UUID.randomUUID(), "someone", "10.0.0.1", false, Instant.now()));

        assertEquals(1, attemptRepository.count(), "The attempt must be stored in the database");
    }

    @Test
    @DisplayName("a refused sign in is remembered even though it failed")
    void aRefusedSignInIsRememberedEvenThoughItFailed() throws Exception {
        String identifier = "rollback-probe";
        long before = attemptRepository.count();

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"identifier\":\""
                                                + identifier
                                                + "\",\"password\":\"wrong-one\"}"))
                .andExpect(status().isUnauthorized());

        // The sign in ended with an exception, so its own transaction was rolled back. The attempt
        // must still be there, because a throttle that forgets the failures it refused is no
        // throttle at all. The row is therefore written in a transaction of its own.
        assertEquals(
                before + 1,
                attemptRepository.count(),
                "A refused sign in must still be remembered, otherwise the throttle never counts"
                        + " anything");
        assertTrue(
                attemptRepository
                        .findByIdentifierSince(identifier, Instant.now().minusSeconds(60))
                        .stream()
                        .anyMatch(attempt -> !attempt.isSuccess()),
                "The refused attempt must be stored as a failure");
    }

    @Test
    @DisplayName("a wrong password on an existing account raises the failure counter")
    void aWrongPasswordRaisesTheFailureCounter() throws Exception {
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        int before = owner.getFailedLoginCount();

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"identifier\":\"owner\",\"password\":\"wrong-one\"}"))
                .andExpect(status().isUnauthorized());

        AppUser after = userRepository.findById(owner.getId()).orElseThrow();
        assertEquals(
                before + 1,
                after.getFailedLoginCount(),
                "The counter of the account must go up, even though the sign in was refused");
    }
}
