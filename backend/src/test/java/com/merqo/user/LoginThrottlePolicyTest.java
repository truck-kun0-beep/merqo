package com.merqo.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.merqo.user.LoginThrottlePolicy.Attempt;
import com.merqo.user.LoginThrottlePolicy.Decision;

/**
 * The unit test of the login throttle policy.
 *
 * <p>It runs without Spring and without waiting. The clock is fixed, so the fifteen minute window
 * is reached by moving the clock instead of by sleeping.
 */
class LoginThrottlePolicyTest {

    /** The fixed moment every test starts at. */
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    /** The clock of the tests. */
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final LoginThrottlePolicy policy = new LoginThrottlePolicy();

    /**
     * A list of failed attempts.
     *
     * @param identifier the account the attempts were made on
     * @param ipAddress the address the attempts came from
     * @param count how many attempts
     * @return the attempts
     */
    private static List<Attempt> failures(String identifier, String ipAddress, int count) {
        List<Attempt> attempts = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            attempts.add(new Attempt(identifier, ipAddress, false, NOW.minusSeconds(30)));
        }
        return attempts;
    }

    @Test
    @DisplayName("the first attempt is allowed")
    void theFirstAttemptIsAllowed() {
        Decision decision = policy.decide("owner", "10.0.0.1", null, List.of(), CLOCK);

        assertTrue(decision.allowed());
        assertFalse(decision.locked());
        assertEquals(null, decision.reason());
    }

    @Test
    @DisplayName("four failures do not lock the account")
    void fourFailuresDoNotLockTheAccount() {
        Decision decision =
                policy.decide("owner", "10.0.0.1", null, failures("owner", "10.0.0.1", 4), CLOCK);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("the fifth failure locks the account")
    void theFifthFailureLocksTheAccount() {
        Decision decision =
                policy.decide(
                        "owner",
                        "10.0.0.1",
                        null,
                        failures("owner", "10.0.0.1", LoginThrottlePolicy.ACCOUNT_FAILURE_LIMIT),
                        CLOCK);

        assertFalse(decision.allowed());
        assertTrue(decision.locked());
        assertEquals("ACCOUNT_LOCKED", decision.reason());
    }

    @Test
    @DisplayName("the lock lasts fifteen minutes")
    void theLockLastsFifteenMinutes() {
        Decision decision =
                policy.decide("owner", "10.0.0.1", null, failures("owner", "10.0.0.1", 5), CLOCK);

        assertEquals(NOW.plus(LoginThrottlePolicy.LOCK_DURATION), decision.retryAfter());
    }

    @Test
    @DisplayName("a locked account is refused even without new failures")
    void aLockedAccountIsRefusedEvenWithoutNewFailures() {
        Decision decision =
                policy.decide("owner", "10.0.0.1", NOW.plusSeconds(60), List.of(), CLOCK);

        assertFalse(decision.allowed());
        assertTrue(decision.locked());
    }

    @Test
    @DisplayName("a lock that has passed no longer blocks the account")
    void aLockThatHasPassedNoLongerBlocksTheAccount() {
        Decision decision =
                policy.decide("owner", "10.0.0.1", NOW.minusSeconds(60), List.of(), CLOCK);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("failures of another account do not lock this one")
    void failuresOfAnotherAccountDoNotLockThisOne() {
        Decision decision =
                policy.decide(
                        "owner", "10.0.0.1", null, failures("somebody.else", "10.0.0.1", 5), CLOCK);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("twenty failures from one address are rate limited")
    void twentyFailuresFromOneAddressAreRateLimited() {
        List<Attempt> attempts = new ArrayList<>();
        for (int index = 0; index < LoginThrottlePolicy.IP_FAILURE_LIMIT; index++) {
            attempts.add(new Attempt("user-" + index, "10.0.0.1", false, NOW.minusSeconds(30)));
        }

        Decision decision = policy.decide("owner", "10.0.0.1", null, attempts, CLOCK);

        assertFalse(decision.allowed());
        assertFalse(decision.locked());
        assertEquals("TOO_MANY_REQUESTS", decision.reason());
    }

    @Test
    @DisplayName("failures from another address do not rate limit this one")
    void failuresFromAnotherAddressDoNotRateLimitThisOne() {
        Decision decision =
                policy.decide(
                        "owner",
                        "10.0.0.2",
                        null,
                        failures("other", "10.0.0.1", LoginThrottlePolicy.IP_FAILURE_LIMIT),
                        CLOCK);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("a failure older than the window is forgotten")
    void aFailureOlderThanTheWindowIsForgotten() {
        Attempt old =
                new Attempt(
                        "owner",
                        "10.0.0.1",
                        false,
                        NOW.minus(LoginThrottlePolicy.WINDOW).minusSeconds(60));

        Decision decision = policy.decide("owner", "10.0.0.1", null, List.of(old), CLOCK);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("a successful attempt does not count as a failure")
    void aSuccessfulAttemptDoesNotCountAsAFailure() {
        Attempt success = new Attempt("owner", "10.0.0.1", true, NOW.minusSeconds(30));

        Decision decision =
                policy.decide("owner", "10.0.0.1", null, List.of(success, success), CLOCK);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("the identifier is compared without the case")
    void theIdentifierIsComparedWithoutTheCase() {
        Decision decision =
                policy.decide("Owner", "10.0.0.1", null, failures("owner", "10.0.0.1", 5), CLOCK);

        assertTrue(decision.locked());
    }
}
