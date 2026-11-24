package com.merqo.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.merqo.audit.AuditLogRepository;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * The test that proves that no secret ever leaves the application.
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test} and looks at three places
 * where a secret could leak:
 *
 * <ol>
 *   <li>The body of a successful answer.
 *   <li>The body of a failed answer.
 *   <li>The values of the audit log.
 * </ol>
 *
 * <p>Three things are treated as secrets: the password of an account, its BCrypt hash and the id of
 * the session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecretsNeverLeakIntegrationTest {

    /** The name of the cookie that holds the session id. */
    private static final String SESSION_COOKIE = "MERQO_SESSION";

    /** A password that follows the account policy. */
    private static final String TEST_PASSWORD = "a-secret-password-9";

    @Autowired private MockMvc mockMvc;

    @Autowired private AppUserRepository userRepository;

    @Autowired private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("no answer of the API carries a password hash")
    void noAnswerOfTheApiCarriesAPasswordHash() throws Exception {
        Cookie session = signIn("owner");

        List<String> bodies =
                List.of(
                        bodyOf(get("/api/v1/auth/me").cookie(session)),
                        bodyOf(get("/api/v1/users?size=50").cookie(session)),
                        bodyOf(get("/api/v1/locations").cookie(session)),
                        bodyOf(get("/api/v1/audit-logs?size=50").cookie(session)),
                        bodyOf(get("/api/v1/settings").cookie(session)),
                        bodyOf(get("/api/v1/auth/status")));

        for (AppUser user : userRepository.findAll()) {
            String hash = user.getPasswordHash();
            assertFalse(hash == null || hash.isBlank(), "The account must have a hash");
            for (String body : bodies) {
                assertFalse(
                        body.contains(hash),
                        "An answer of the API carried a password hash of the account "
                                + user.getUsername());
                assertFalse(
                        body.contains("$2a$"),
                        "An answer of the API carried something that looks like a BCrypt hash");
            }
        }
    }

    @Test
    @DisplayName("no answer of the API carries the session id")
    void noAnswerOfTheApiCarriesTheSessionId() throws Exception {
        MvcResult login =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"owner\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie session = login.getResponse().getCookie(SESSION_COOKIE);
        assertTrue(session != null, "The sign in must write the session cookie");

        String body = bodyOf(get("/api/v1/auth/me").cookie(session));

        assertFalse(
                body.contains(session.getValue()), "The answer of the API carried the session id");
    }

    @Test
    @DisplayName("no answer of the API carries the password of the demo accounts")
    void noAnswerOfTheApiCarriesThePasswordOfTheDemoAccounts() throws Exception {
        Cookie session = signIn("owner");

        String body = bodyOf(get("/api/v1/users?size=50").cookie(session));

        assertFalse(body.contains(TEST_PASSWORD), "An answer of the API carried a password");
        assertFalse(
                body.toLowerCase(Locale.ROOT).contains("passwordhash"),
                "An answer of the API carried the name of the password hash");
    }

    @Test
    @DisplayName("no value of the audit log carries a password")
    void noValueOfTheAuditLogCarriesAPassword() {
        auditLogRepository
                .findAll()
                .forEach(
                        row -> {
                            String values =
                                    String.valueOf(row.getOldValues())
                                            + String.valueOf(row.getNewValues());
                            assertFalse(
                                    values.contains("$2a$"),
                                    "The audit log carried something that looks like a BCrypt"
                                            + " hash");
                            assertFalse(
                                    values.contains("password\"") && values.contains(TEST_PASSWORD),
                                    "The audit log carried a password");
                        });
    }

    @Test
    @DisplayName("a failed sign in never says which part was wrong")
    void aFailedSignInNeverSaysWhichPartWasWrong() throws Exception {
        String wrongPassword =
                bodyOf(
                        post("/api/v1/auth/login")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"identifier\":\"owner\",\"password\":\"wrong-one\"}"));
        String unknownAccount =
                bodyOf(
                        post("/api/v1/auth/login")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"identifier\":\"nobody-here\",\"password\":\"wrong-one\"}"));

        assertTrue(
                wrongPassword.contains("Invalid username or password"),
                "The answer of a wrong password must be the generic one");
        assertTrue(
                unknownAccount.contains("Invalid username or password"),
                "The answer of an unknown account must be the generic one too");
        assertFalse(
                unknownAccount.contains("not exist") || unknownAccount.contains("unknown user"),
                "The answer must not say that the account does not exist");
    }

    /**
     * Signs a demo account in and returns the cookie that carries the session.
     *
     * @param accountKey the key of the demo account
     * @return the session cookie
     * @throws Exception when the login fails
     */
    private Cookie signIn(String accountKey) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"" + accountKey + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        return result.getResponse().getCookie(SESSION_COOKIE);
    }

    /**
     * The body of the answer of a request.
     *
     * @param builder the request
     * @return the body
     * @throws Exception when the request fails
     */
    private String bodyOf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mockMvc.perform(builder)
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
