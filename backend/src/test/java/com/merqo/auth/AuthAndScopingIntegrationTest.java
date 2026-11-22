package com.merqo.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.merqo.access.LocationAssignmentRepository;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.location.LocationRepository;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * The integration test of the login and the scoping rules.
 *
 * <p>This test runs the real application against the real PostgreSQL database {@code merqo_test},
 * with the real security filter chain and the real demo seed. It proves the three rules that the
 * whole access model rests on:
 *
 * <ol>
 *   <li>A request without a session is refused with 401.
 *   <li>A request that changes something without the CSRF token is refused with 403.
 *   <li>A manager only ever sees the locations of their own branches, and a location outside that
 *       scope is answered with 404, not with 403, so the caller cannot even learn that it exists.
 * </ol>
 *
 * <p><b>The session travels in a cookie.</b> Spring Session keeps the session in PostgreSQL and
 * writes only the session id into the {@code MERQO_SESSION} cookie, so a second request has to
 * carry that cookie. The servlet session of the first request is not the session of the second one,
 * which is exactly what a browser does not do.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAndScopingIntegrationTest {

    /** The name of the cookie that holds the session id. */
    private static final String SESSION_COOKIE = "MERQO_SESSION";

    @Autowired private MockMvc mockMvc;

    @Autowired private AppUserRepository userRepository;

    @Autowired private LocationRepository locationRepository;

    @Autowired private LocationAssignmentRepository assignmentRepository;

    @Test
    @DisplayName("a request without a session is refused with 401")
    void anonymousRequestIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/locations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("the demo accounts are public, so the login page can show them")
    void demoAccountsArePublic() throws Exception {
        mockMvc.perform(get("/api/v1/demo/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountKey").value("owner"));
    }

    @Test
    @DisplayName("the setup status is public")
    void authStatusIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoMode").value(true))
                .andExpect(jsonPath("$.setupRequired").value(false));
    }

    @Test
    @DisplayName("a login without the CSRF token is refused with 403")
    void loginWithoutCsrfTokenIsRefused() throws Exception {
        mockMvc.perform(
                        post("/api/v1/demo/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"accountKey\":\"owner\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("a login with the CSRF token starts a session")
    void loginWithCsrfTokenStartsASession() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"owner\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.accountType").value("OWNER"))
                        .andReturn();

        assertNotNull(
                sessionCookie(result.getResponse()), "The login must write the session cookie");
    }

    @Test
    @DisplayName("a broken JSON body is answered with 400, not with 500")
    void brokenJsonIsBadRequest() throws Exception {
        mockMvc.perform(
                        post("/api/v1/demo/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("a wrong HTTP method is answered with 400, not with 500")
    void wrongMethodIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/status").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("a manager only sees the locations of their own branches")
    void managerSeesOnlyTheirOwnLocations() throws Exception {
        AppUser manager = userRepository.findByDemoKey("manager-dm").orElseThrow();
        var dm = locationRepository.findByCodeIgnoreCase("DM").orElseThrow();
        var mp = locationRepository.findByCodeIgnoreCase("MP").orElseThrow();
        var ut = locationRepository.findByCodeIgnoreCase("UT").orElseThrow();

        Cookie session = signIn("manager-dm");

        String body =
                mockMvc.perform(get("/api/v1/locations").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertTrue(body.contains(dm.getName()), "The manager must see Dhanmondi");
        assertTrue(body.contains(mp.getName()), "The manager must see Mirpur");
        assertTrue(!body.contains(ut.getName()), "The manager must not see Uttara");

        assertTrue(
                assignmentRepository
                        .findActiveLocationIdsByUserId(manager.getId())
                        .contains(dm.getId()));
    }

    @Test
    @DisplayName("a location outside the scope of the manager is answered with 404")
    void locationOutsideTheScopeIsNotFound() throws Exception {
        var ut = locationRepository.findByCodeIgnoreCase("UT").orElseThrow();

        Cookie session = signIn("manager-dm");

        mockMvc.perform(get("/api/v1/locations/{id}", ut.getId()).cookie(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("the owner sees every location")
    void ownerSeesEveryLocation() throws Exception {
        Cookie session = signIn("owner");

        mockMvc.perform(get("/api/v1/locations").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("the list of accounts is sorted and paginated instead of failing")
    void userListIsSortedAndPaginated() throws Exception {
        Cookie session = signIn("owner");

        mockMvc.perform(get("/api/v1/users?size=50").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(6));

        mockMvc.perform(get("/api/v1/users?sort=username&direction=ASC&size=50").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].username").value("demo.cashier"));
    }

    @Test
    @DisplayName("a manager may read the audit log of their own branches")
    void managerMayReadTheAuditLog() throws Exception {
        Cookie session = signIn("manager-dm");

        mockMvc.perform(get("/api/v1/audit-logs?size=10").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("a manager may not read the business settings")
    void managerMayNotReadTheSettings() throws Exception {
        Cookie session = signIn("manager-dm");

        mockMvc.perform(get("/api/v1/settings").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("the password of a demo account is never accepted")
    void demoAccountCannotSignInWithAPassword() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"identifier\":\"demo.manager.dm\",\"password\":\"anything\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("the number of demo locations is the one the seeder writes")
    void demoLocationsAreSeeded() {
        assertEquals(4, locationRepository.count());
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
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"" + accountKey + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        return sessionCookie(result.getResponse());
    }

    /**
     * The session cookie of one answer.
     *
     * @param response the answer of the login
     * @return the cookie, or {@code null} when the answer carries none
     */
    private static Cookie sessionCookie(MockHttpServletResponse response) {
        Cookie cookie = response.getCookie(SESSION_COOKIE);
        return cookie;
    }

    /**
     * The CSRF token of a request.
     *
     * @return the request post processor that adds the token
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return CsrfTestSupport.csrf();
    }
}
