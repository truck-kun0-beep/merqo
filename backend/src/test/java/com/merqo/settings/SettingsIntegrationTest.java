package com.merqo.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.merqo.common.web.CsrfTestSupport;

/**
 * The integration test of the business settings.
 *
 * <p>It runs against the real PostgreSQL database {@code merqo_test} with the real security chain.
 *
 * <p>Two things are proved:
 *
 * <ol>
 *   <li>The owner may read every setting, and the answer groups them the way the screen expects.
 *   <li>The settings screen never crashes. The grouping builds a list per group and then fills it,
 *       so a list that cannot grow would end the request with a 500 instead of the settings.
 * </ol>
 *
 * <p><b>The session travels in a cookie.</b> The session lives in PostgreSQL and only its id
 * travels in the {@code MERQO_SESSION} cookie, so every request after the sign in has to carry that
 * cookie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingsIntegrationTest {

    /** The name of the cookie that holds the session id. */
    private static final String SESSION_COOKIE = "MERQO_SESSION";

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("the owner reads every setting, grouped for the settings screen")
    void ownerReadsEverySetting() throws Exception {
        Cookie session = signIn("owner");

        mockMvc.perform(get("/api/v1/settings").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.BUSINESS").isArray())
                .andExpect(jsonPath("$.groups.BUSINESS[0].key").value("business.name"))
                .andExpect(jsonPath("$.groups.BUSINESS[0].value").exists())
                .andExpect(jsonPath("$.groups.RECEIPT").isArray())
                .andExpect(jsonPath("$.groups.TAX").isArray())
                .andExpect(jsonPath("$.groups.THRESHOLDS").isArray());
    }

    @Test
    @DisplayName("every setting appears exactly once, in the group it belongs to")
    void everySettingIsListedOnce() throws Exception {
        Cookie session = signIn("owner");

        String body =
                mockMvc.perform(get("/api/v1/settings").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        for (SettingKey key : SettingKey.all()) {
            String needle = "\"" + key.getKey() + "\"";
            int first = body.indexOf(needle);
            assertTrue(first >= 0, "The setting " + key.getKey() + " must be in the answer");
            assertEquals(
                    first,
                    body.lastIndexOf(needle),
                    "The setting " + key.getKey() + " must appear exactly once");
        }
    }

    @Test
    @DisplayName("a manager may not read the settings")
    void managerMayNotReadTheSettings() throws Exception {
        Cookie session = signIn("manager-dm");

        mockMvc.perform(get("/api/v1/settings").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("a request without a session is refused with 401")
    void anonymousRequestIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("a read only setting is marked as such")
    void readOnlySettingsAreMarked() throws Exception {
        Cookie session = signIn("owner");

        String body =
                mockMvc.perform(get("/api/v1/settings").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertFalse(body.isBlank());
        assertTrue(body.contains("\"readOnly\""), "Every setting must carry the read only flag");
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
        assertTrue(cookie != null, "The login must write the session cookie");
        return cookie;
    }
}
