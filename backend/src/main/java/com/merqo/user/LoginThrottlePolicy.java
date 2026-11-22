package com.merqo.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Decides whether a login attempt is allowed, locked or rate limited.
 *
 * <p>Responsibility: read the recent attempt history and produce one decision. The class holds no
 * state of its own; the history comes in and a {@link Clock} tells it what time it is. That is what
 * makes the rules testable without a database and without sleeping.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>5 failed attempts for one account within 15 minutes lock that account for 15 minutes.
 *   <li>20 failed attempts from one IP address within 15 minutes are refused with a rate limit.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the class only decides; it never writes to the database.
 *   <li><b>Dependency injection</b> - the {@link Clock} is injected, so a test can use a fixed
 *       clock.
 *   <li><b>Pure function</b> - history in, decision out.
 *   <li><b>Value object</b> - {@link Decision} and {@link Attempt} are immutable records.
 * </ul>
 */
public class LoginThrottlePolicy {

    /** How many failed attempts for one account lock it. */
    public static final int ACCOUNT_FAILURE_LIMIT = 5;

    /** How many failed attempts from one IP address are rate limited. */
    public static final int IP_FAILURE_LIMIT = 20;

    /** The window in which the failures are counted. */
    public static final Duration WINDOW = Duration.ofMinutes(15);

    /** How long an account stays locked. */
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /**
     * Decides what happens to a login attempt.
     *
     * @param identifier the login name or email that was typed, lowercased
     * @param ipAddress the address the request came from
     * @param lockedUntil when the account is locked until, or {@code null} when it is not locked
     * @param recentAttempts the attempts of the last 24 hours
     * @param clock the clock that tells the current time
     * @return the decision
     */
    public Decision decide(
            String identifier,
            String ipAddress,
            Instant lockedUntil,
            List<Attempt> recentAttempts,
            Clock clock) {
        Instant now = clock.instant();

        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            return Decision.locked(lockedUntil);
        }

        long accountFailures =
                countFailures(recentAttempts, now, attempt -> sameIdentifier(attempt, identifier));
        if (accountFailures >= ACCOUNT_FAILURE_LIMIT) {
            Instant unlockAt = now.plus(LOCK_DURATION);
            return Decision.locked(unlockAt);
        }

        long ipFailures = countFailures(recentAttempts, now, attempt -> sameIp(attempt, ipAddress));
        if (ipFailures >= IP_FAILURE_LIMIT) {
            return Decision.rateLimited(now.plus(WINDOW));
        }

        return Decision.permitted();
    }

    private long countFailures(
            List<Attempt> attempts, Instant now, java.util.function.Predicate<Attempt> filter) {
        Instant windowStart = now.minus(WINDOW);
        return attempts.stream()
                .filter(attempt -> !attempt.success())
                .filter(attempt -> !attempt.attemptedAt().isBefore(windowStart))
                .filter(attempt -> !attempt.attemptedAt().isAfter(now))
                .filter(filter)
                .count();
    }

    private boolean sameIdentifier(Attempt attempt, String identifier) {
        if (identifier == null || attempt.identifier() == null) {
            return false;
        }
        return attempt.identifier().equalsIgnoreCase(identifier);
    }

    private boolean sameIp(Attempt attempt, String ipAddress) {
        if (ipAddress == null || attempt.ipAddress() == null) {
            return false;
        }
        return attempt.ipAddress().equals(ipAddress);
    }

    /**
     * One recorded login attempt.
     *
     * @param identifier the login name or email that was typed, lowercased
     * @param ipAddress the address the request came from
     * @param success whether the attempt succeeded
     * @param attemptedAt when the attempt happened
     */
    public record Attempt(
            String identifier, String ipAddress, boolean success, Instant attemptedAt) {

        /**
         * Creates an attempt.
         *
         * @param identifier the identifier
         * @param ipAddress the address
         * @param success whether it succeeded
         * @param attemptedAt when it happened
         */
        public Attempt {
            if (identifier != null) {
                identifier = identifier.trim().toLowerCase(java.util.Locale.ROOT);
            }
        }
    }

    /**
     * The outcome of the throttle check.
     *
     * @param allowed whether the attempt may continue
     * @param locked whether the account is locked
     * @param retryAfter when the caller may try again, {@code null} when there is no wait
     * @param reason the stable error code, {@code null} when the attempt is allowed
     */
    public record Decision(boolean allowed, boolean locked, Instant retryAfter, String reason) {

        /**
         * Creates a decision.
         *
         * @param allowed whether the attempt may continue
         * @param locked whether the account is locked
         * @param retryAfter when to try again
         * @param reason the error code
         */
        public Decision {
            if (allowed && reason != null) {
                throw new IllegalArgumentException("An allowed decision has no reason");
            }
            if (!allowed && reason == null) {
                throw new IllegalArgumentException("A refused decision needs a reason");
            }
        }

        /**
         * The attempt may continue.
         *
         * @return the decision
         */
        public static Decision permitted() {
            return new Decision(true, false, null, null);
        }

        /**
         * The account is locked until a moment in time.
         *
         * @param until when the lock ends
         * @return the decision
         */
        public static Decision locked(Instant until) {
            return new Decision(false, true, until, "ACCOUNT_LOCKED");
        }

        /**
         * Too many attempts from this address.
         *
         * @param until when the caller may try again
         * @return the decision
         */
        public static Decision rateLimited(Instant until) {
            return new Decision(false, false, until, "TOO_MANY_REQUESTS");
        }
    }
}
