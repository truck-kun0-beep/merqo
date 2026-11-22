package com.merqo.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.merqo.common.web.CsrfTestSupport;

/**
 * The integration test of the forced password change.
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test} with the real security chain.
 *
 * <p>The rule is proved from both sides:
 *
 * <ol>
 *   <li>An account that still has to change its password may not read the business screens. The
 *       answer is 403 with the code {@code PASSWORD_CHANGE_REQUIRED}, so the frontend can send the
 *       user to the change password screen.
 *   <li>The sign in screens stay open, otherwise the user could not fix the password at all.
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordChangeRequiredIntegrationTest {

    /** The name of the cookie that holds the session id. */
    private static final String SESSION_COOKIE = "MERQO_SESSION";

    @Autowired private MockMvc mockMvc;

    @Autowired private AppUserRepository userRepository;

    /** Puts the flag back, so this test never changes the state of the next test. */
    @AfterEach
    void clearThePendingChange() {
        AppUser owner = userRepository.findByDemoKey("owner").orElseThrow();
        owner.setMustChangePassword(false);
        userRepository.save(owner);
    }

    @Test
    @DisplayName("an account with a pending password change may not read the business screens")
    void aPendingChangeBlocksTheBusinessScreens() throws Exception {
        requirePasswordChange("owner");

        Cookie session = signIn("owner");

        mockMvc.perform(get("/api/v1/locations").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    @DisplayName("the sign in screens stay open while the password change is pending")
    void theSignInScreensStayOpen() throws Exception {
        requirePasswordChange("owner");

        Cookie session = signIn("owner");

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    @DisplayName("the change password endpoint itself is not blocked")
    void theChangePasswordEndpointIsNotBlocked() throws Exception {
        requirePasswordChange("owner");

        Cookie session = signIn("owner");

        mockMvc.perform(
                        post("/api/v1/auth/change-password")
                                .cookie(session)
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"currentPassword\":\"wrong-password\",\"newPassword\":\""
                                                + "another-strong-one\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("an account without a pending change reads the business screens as usual")
    void anAccountWithoutAPendingChangeIsNotBlocked() throws Exception {
        Cookie session = signIn("owner");

        mockMvc.perform(get("/api/v1/locations").cookie(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the allowed paths are the sign in screens and nothing else")
    void theAllowedPathsAreTheSignInScreens() {
        assertTrue(PasswordChangePolicy.isAllowedPath("/api/v1/auth/me"));
        assertTrue(PasswordChangePolicy.isAllowedPath("/api/v1/auth/change-password"));
        assertTrue(PasswordChangePolicy.isAllowedPath("/api/v1/auth/logout"));
        assertFalse(PasswordChangePolicy.isAllowedPath("/api/v1/locations"));
        assertFalse(PasswordChangePolicy.isAllowedPath("/api/v1/settings"));
        assertFalse(PasswordChangePolicy.isAllowedPath("/api/v1/users"));
        assertFalse(PasswordChangePolicy.isAllowedPath("/api/v1/audit-logs"));
    }

    /**
     * Marks a demo account as having to change its password.
     *
     * @param accountKey the key of the demo account
     */
    private void requirePasswordChange(String accountKey) {
        AppUser owner = userRepository.findByDemoKey(accountKey).orElseThrow();
        owner.setMustChangePassword(true);
        userRepository.save(owner);
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
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertNotNull(cookie, "The login must write the session cookie");
        return cookie;
    }
}
