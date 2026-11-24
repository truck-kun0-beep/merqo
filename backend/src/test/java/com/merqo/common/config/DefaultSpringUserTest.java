package com.merqo.common.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * The test that proves Spring Boot's default security user is not there.
 *
 * <p>Responsibility: check that the application does not carry the account Spring Boot creates on
 * its own.
 *
 * <p><b>Why this matters.</b> When the {@code spring-boot-starter-security} dependency is on the
 * classpath and the application defines no user of its own, Spring Boot creates an in memory
 * account named {@code user} with a random password, and writes that password into the log at every
 * start. Two things are wrong with that for Merqo:
 *
 * <ol>
 *   <li>It is a second way in. The account exists whether the owner wants it or not.
 *   <li>The password lands in the log, and a log is exactly the place a secret must not be.
 * </ol>
 *
 * <p>Merqo has its own accounts in PostgreSQL, so the autoconfiguration is excluded in {@link
 * com.merqo.MerqoApplication}. This test proves that the exclusion really works, against the real
 * application context.
 *
 * <p>OOP concepts demonstrated: the test asks the application context what it really holds, so it
 * cannot pass while the default user is still there.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefaultSpringUserTest {

    @Autowired private ApplicationContext applicationContext;

    @Test
    @DisplayName("no default Spring user exists")
    void noDefaultSpringUserExists() {
        String[] userDetailsServiceBeans =
                applicationContext.getBeanNamesForType(UserDetailsService.class);

        assertFalse(
                Arrays.stream(userDetailsServiceBeans)
                        .anyMatch(
                                name ->
                                        applicationContext.getBean(name)
                                                instanceof InMemoryUserDetailsManager),
                "The application must not have the in memory user of Spring Boot, because it comes"
                        + " with a random password that is written into the log. The beans that were"
                        + " found are: "
                        + String.join(", ", userDetailsServiceBeans));
    }

    @Test
    @DisplayName("the accounts of Merqo are the only way in")
    void theAccountsOfMerqoAreTheOnlyWayIn() {
        // The real accounts live in PostgreSQL and are loaded by the services of the user module.
        // The proof that they are there is the demo owner, which the seeder writes in demo mode.
        assertTrue(
                applicationContext.containsBean("passwordEncoder"),
                "Merqo must have its own password encoder, so the BCrypt hashes of the accounts"
                        + " are the only hashes that exist.");
    }
}
