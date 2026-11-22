package com.merqo.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.merqo.common.web.CsrfTestSupport;

/**
 * The base class of every test that needs a signed in user.
 *
 * <p>Responsibility: give the later prompts one short way to sign in and to assert the two scope
 * answers, so the same rule is proved the same way everywhere.
 *
 * <p>The rules this helper encodes:
 *
 * <ul>
 *   <li>A record outside the caller's scope is answered with <b>404 NOT_FOUND</b>, never with 403,
 *       so the caller cannot even learn that the record exists.
 *   <li>A record inside the caller's scope that the caller has no permission for is answered with
 *       <b>403 FORBIDDEN</b>.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Inheritance</b> - every integration test extends this class and gets the helpers.
 *   <li><b>Template Method</b> - the sign in flow is written once here.
 *   <li><b>Encapsulation</b> - the cookie name and the error codes live here, so a later change is
 *       made in one place.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ScopeTestSupport {

    /** The name of the cookie that holds the session id. */
    protected static final String SESSION_COOKIE = "MERQO_SESSION";

    @Autowired protected MockMvc mockMvc;

    /**
     * Signs a demo account in and returns the cookie that carries the session.
     *
     * @param accountKey the key of the demo account, for example {@code owner}
     * @return the session cookie
     * @throws Exception when the login fails
     */
    protected Cookie signIn(String accountKey) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/demo/login")
                                        .with(CsrfTestSupport.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accountKey\":\"" + accountKey + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        if (cookie == null) {
            throw new AssertionError("The sign in did not write the session cookie");
        }
        return cookie;
    }

    /**
     * Asserts that a request is refused because the record is outside the caller's scope.
     *
     * @param result the result of the request
     * @throws Exception when the request fails
     */
    protected void assertOutOfScope(org.springframework.test.web.servlet.ResultActions result)
            throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    /**
     * Asserts that a request is refused because the caller lacks the permission.
     *
     * @param result the result of the request
     * @throws Exception when the request fails
     */
    protected void assertForbidden(org.springframework.test.web.servlet.ResultActions result)
            throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }
}
