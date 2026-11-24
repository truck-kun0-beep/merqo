package com.merqo.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.merqo.access.AccountType;
import com.merqo.common.web.CsrfTestSupport;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;
import com.merqo.user.LoginAttemptRepository;

/**
 * The test that proves that demo mode really is off.
 *
 * <p>It runs with {@code merqo.demo-mode=false}, so it uses a different application context than
 * the other tests.
 *
 * <p>What it proves:
 *
 * <ol>
 *   <li>The demo endpoints answer with 404 when demo mode is off. They do not answer with 403 and
 *       they do not leak that they exist.
 *   <li>The login endpoint refuses a demo account, so a demo account that was left in an old
 *       database can never be used when demo mode is off.
 *   <li>The demo seeder is <b>not registered</b> when demo mode is off, so it cannot write
 *       anything. This is the proof that matters, because the test database is shared with the
 *       other tests, which do run the demo seeder.
 * </ol>
 *
 * <p><b>Why the suite writes its own demo account.</b> The account is written and removed again in
 * the same test, so the shared database keeps the number of accounts the other suites expect. The
 * account is signed in with the <b>correct</b> password, so the refusal can only come from the demo
 * rule. A refused attempt would write an audit row, and the audit log is append only, so such an
 * account could never be removed again.
 *
 * <p>The suite signs in from its own address, so it never shares the counter of the address limit
 * with the other suites.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "merqo.demo-mode=false")
class DemoModeOffIntegrationTest {

    /** The address this suite signs in from, so it never shares the address limit. */
    private static final String SUITE_ADDRESS = "10.9.9.20";

    /** The password of the demo account of this suite. It is known to the test only. */
    private static final String KNOWN_PASSWORD = "the-demo-password-of-this-suite";

    /** The prefix of the throwaway accounts this suite writes. */
    private static final String OLD_DEMO_PREFIX = "old-demo-";

    @Autowired private MockMvc mockMvc;

    @Autowired private ApplicationContext applicationContext;

    @Autowired private AppUserRepository userRepository;

    @Autowired private LoginAttemptRepository attemptRepository;

    @Autowired private PasswordEncoder passwordEncoder;

    /** The demo account of the current test. */
    private AppUser demoAccount;

    @BeforeEach
    void clearTheThrottleStateOfThisSuite() {
        // The throttle counts the attempts of the last fifteen minutes. The rows of this suite are
        // removed, so the suite always starts from the same state and its answer is the same as on
        // a fresh database.
        attemptRepository
                .findForThrottleSince(SUITE_ADDRESS, SUITE_ADDRESS, Instant.EPOCH)
                .forEach(attemptRepository::delete);
    }

    @AfterEach
    void removeTheDemoAccountOfThisSuite() {
        // The database is shared with the other suites, and the number of accounts is asserted by
        // one of them. The account of this suite is therefore removed again.
        //
        // The account can be removed, because it is signed in with the correct password and no
        // failed attempt is recorded for it. A failed attempt would write an audit row, and the
        // audit log is append only, so such an account could never be deleted.
        attemptRepository
                .findForThrottleSince(SUITE_ADDRESS, SUITE_ADDRESS, Instant.EPOCH)
                .forEach(attemptRepository::delete);
        if (demoAccount == null) {
            return;
        }
        userRepository
                .findByUsernameIgnoreCase(demoAccount.getUsername())
                .ifPresent(userRepository::delete);
        demoAccount = null;
    }

    @Test
    @DisplayName("the list of demo accounts answers with 404 when demo mode is off")
    void theListOfDemoAccountsAnswersWith404WhenDemoModeIsOff() throws Exception {
        mockMvc.perform(get("/api/v1/demo/accounts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("the demo login answers with 404 when demo mode is off")
    void theDemoLoginAnswersWith404WhenDemoModeIsOff() throws Exception {
        mockMvc.perform(
                        post("/api/v1/demo/login")
                                .with(CsrfTestSupport.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"accountKey\":\"owner\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("the setup status says that demo mode is off")
    void theSetupStatusSaysThatDemoModeIsOff() throws Exception {
        mockMvc.perform(get("/api/v1/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoMode").value(false));
    }

    @Test
    @DisplayName("the demo seeder is not even registered when demo mode is off")
    void theDemoSeederIsNotEvenRegisteredWhenDemoModeIsOff() {
        assertFalse(
                applicationContext.getBeanNamesForType(DemoSeeder.class).length > 0,
                "The demo seeder must not exist when demo mode is off, so it cannot write anything");
        assertEquals(
                0,
                applicationContext.getBeanNamesForType(DemoDataContributor.class).length,
                "No demo contributor may exist when demo mode is off");
    }

    @Test
    @DisplayName("a demo account cannot sign in with a password when demo mode is off")
    void aDemoAccountCannotSignInWithAPasswordWhenDemoModeIsOff() throws Exception {
        demoAccount = writeADemoAccount();

        // The password is the correct one, so the refusal can only come from the demo rule.
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .with(CsrfTestSupport.csrf())
                                .header("X-Forwarded-For", SUITE_ADDRESS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"identifier\":\""
                                                + demoAccount.getUsername()
                                                + "\",\"password\":\""
                                                + KNOWN_PASSWORD
                                                + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("the demo accounts of an old database are refused when demo mode is off")
    void theDemoAccountsOfAnOldDatabaseAreRefusedWhenDemoModeIsOff() throws Exception {
        // A database that was used in demo mode may still hold the demo accounts. When the mode is
        // switched off they must be useless: the one click login does not exist any more, so
        // there is no way in at all.
        demoAccount = writeADemoAccount();

        assertTrue(demoAccount.isDemo(), "The account must be marked as a demo account");
        assertEquals(
                demoAccount.getUsername(),
                demoAccount.getDemoKey(),
                "The account must carry its demo key, so the seeder would find it again");

        mockMvc.perform(
                        post("/api/v1/demo/login")
                                .with(CsrfTestSupport.csrf())
                                .header("X-Forwarded-For", SUITE_ADDRESS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"accountKey\":\"" + demoAccount.getDemoKey() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    /**
     * Writes one demo account, the way an old demo database would hold it.
     *
     * @return the account
     */
    private AppUser writeADemoAccount() {
        String username = OLD_DEMO_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        AppUser account =
                new AppUser(
                        UUID.randomUUID(),
                        username,
                        null,
                        "Old demo account",
                        passwordEncoder.encode(KNOWN_PASSWORD),
                        AccountType.STAFF,
                        Instant.now());
        account.setDemo(true);
        account.setDemoKey(username);
        return userRepository.save(account);
    }
}
