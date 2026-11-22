package com.merqo.user;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.common.error.BusinessException;
import com.merqo.common.error.ErrorCodes;

/**
 * The login and session rules of Merqo.
 *
 * <p>Responsibility: check the credentials, apply the throttle, keep the lock counter and write the
 * audit rows of the login.
 *
 * <p>The security rules that matter here:
 *
 * <ul>
 *   <li>The error is always the same: "Invalid username or password". The caller never learns
 *       whether the account exists.
 *   <li>A hash is computed even when the account does not exist, so the answer takes the same time
 *       either way and the timing does not leak the existence of an account.
 *   <li>The password, the hash and the session id never appear in a response, a log line, an audit
 *       value or an error message.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Dependency injection</b> - the password encoder, the clock and the repositories are
 *       injected.
 *   <li><b>Delegation</b> - the throttle rules live in {@link LoginThrottlePolicy}, the password
 *       rules in {@link PasswordPolicy}; this class only orchestrates.
 *   <li><b>Template Method</b> - the audit events are built with {@link AuditEvent.Builder}.
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** The message that is returned for every wrong credential. */
    public static final String GENERIC_LOGIN_ERROR = "Invalid username or password";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottlePolicy throttlePolicy;
    private final PasswordPolicy passwordPolicy;
    private final AuditService auditService;
    private final LoginAttemptRecorder attemptRecorder;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param userRepository the accounts
     * @param attemptRepository the login attempts
     * @param passwordEncoder the BCrypt encoder
     * @param throttlePolicy the throttle rules
     * @param passwordPolicy the password rules
     * @param auditService the audit log
     * @param attemptRecorder the writer of the throttle rows
     * @param clock the clock
     */
    public AuthService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            LoginThrottlePolicy throttlePolicy,
            PasswordPolicy passwordPolicy,
            AuditService auditService,
            LoginAttemptRecorder attemptRecorder,
            Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.throttlePolicy = throttlePolicy;
        this.passwordPolicy = passwordPolicy;
        this.auditService = auditService;
        this.attemptRecorder = attemptRecorder;
        this.clock = clock;
    }

    /**
     * Checks the credentials of a login attempt.
     *
     * @param identifier the login name or the email address
     * @param password the password
     * @param ipAddress the address the request came from
     * @param correlationId the correlation id of the request
     * @return the account that signed in
     * @throws BusinessException when the credentials are wrong, the account is locked or the
     *     address sent too many attempts
     */
    @Transactional
    public AppUser login(
            String identifier, String password, String ipAddress, String correlationId) {
        Instant now = clock.instant();
        String normalized =
                identifier == null ? "" : identifier.trim().toLowerCase(java.util.Locale.ROOT);

        Optional<AppUser> found = userRepository.findByIdentifier(normalized);
        AppUser user = found.orElse(null);

        // A hash is computed even when the account does not exist, so that both answers take the
        // same time. The hash is thrown away immediately.
        String storedHash =
                user == null ? passwordEncoder.encode(randomPassword()) : user.getPasswordHash();
        boolean passwordMatches =
                passwordEncoder.matches(password == null ? "" : password, storedHash);

        LoginThrottlePolicy.Decision decision = decideThrottle(normalized, ipAddress, user, now);

        if (!decision.allowed()) {
            // The attempt is written in a transaction of its own. The sign in ends with an
            // exception, so a row written in this transaction would be rolled back with it and
            // the throttle would never count anything.
            attemptRecorder.recordFailure(normalized, ipAddress, now);
            if (decision.locked()) {
                if (user != null) {
                    attemptRecorder.lockAccount(
                            user, decision.retryAfter(), ipAddress, correlationId);
                }
                throw new LoginLockedException(decision.retryAfter());
            }
            throw new TooManyRequestsException(decision.retryAfter());
        }

        if (user == null || !passwordMatches || !user.isActive()) {
            attemptRecorder.recordFailure(normalized, ipAddress, now);
            if (user != null) {
                boolean locked =
                        attemptRecorder.registerAccountFailure(user, now, ipAddress, correlationId);
                if (locked) {
                    // The attempt that reaches the limit is the one that locks the account, so the
                    // caller is told the truth at once instead of being told "wrong password" and
                    // only learning about the lock on the next try.
                    throw new LoginLockedException(now.plus(LoginThrottlePolicy.LOCK_DURATION));
                }
            }
            throw new InvalidCredentialsException();
        }

        // A demo account never signs in with a password. It only signs in through the one click
        // login, which is only available in demo mode.
        if (user.isDemo()) {
            throw new InvalidCredentialsException();
        }

        attemptRecorder.recordSuccess(normalized, ipAddress, now);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        userRepository.save(user);

        auditService.record(
                new AuditEvent.Builder(AuditAction.LOGIN_SUCCEEDED, "app_users")
                        .entityId(user.getId())
                        .actor(user.getId(), user.getUsername())
                        .ipAddress(ipAddress)
                        .correlationId(correlationId)
                        .build());

        return user;
    }

    /**
     * Signs a demo account in without a password.
     *
     * <p>Only the demo service may call this, and it only exists in demo mode.
     *
     * @param demoKey the demo key
     * @param ipAddress the address the request came from
     * @param correlationId the correlation id of the request
     * @return the demo account
     */
    @Transactional
    public AppUser loginDemoAccount(String demoKey, String ipAddress, String correlationId) {
        AppUser user =
                userRepository
                        .findByDemoKey(demoKey)
                        .orElseThrow(() -> new InvalidCredentialsException());
        if (!user.isActive()) {
            throw new InvalidCredentialsException();
        }
        Instant now = clock.instant();
        attemptRecorder.recordSuccess(user.getUsername(), ipAddress, now);
        user.setLastLoginAt(now);
        userRepository.save(user);
        auditService.record(
                new AuditEvent.Builder(AuditAction.LOGIN_SUCCEEDED, "app_users")
                        .entityId(user.getId())
                        .actor(user.getId(), user.getUsername())
                        .added("demoKey", demoKey)
                        .ipAddress(ipAddress)
                        .correlationId(correlationId)
                        .build());
        return user;
    }

    /**
     * Changes the password of the signed in user.
     *
     * @param user the signed in user
     * @param currentPassword the current password
     * @param newPassword the new password
     * @param ipAddress the address the request came from
     * @param correlationId the correlation id of the request
     */
    @Transactional
    public void changeOwnPassword(
            AppUser user,
            String currentPassword,
            String newPassword,
            String ipAddress,
            String correlationId) {
        if (!passwordEncoder.matches(
                currentPassword == null ? "" : currentPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        PasswordPolicy.Result result = passwordPolicy.validate(newPassword, user.getUsername());
        if (!result.valid()) {
            throw new PasswordPolicyException(result.message());
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setUpdatedAt(clock.instant());
        userRepository.save(user);
        auditService.record(
                new AuditEvent.Builder(AuditAction.PASSWORD_CHANGED, "app_users")
                        .entityId(user.getId())
                        .actor(user.getId(), user.getUsername())
                        .ipAddress(ipAddress)
                        .correlationId(correlationId)
                        .build());
    }

    private LoginThrottlePolicy.Decision decideThrottle(
            String identifier, String ipAddress, AppUser user, Instant now) {
        Instant windowStart = now.minus(LoginThrottlePolicy.WINDOW);
        // One query, one list. The rows that match the account and the address at the same time
        // are only returned once, so a failure is never counted twice.
        List<LoginAttempt> attempts =
                attemptRecorder.findForThrottleSince(identifier, ipAddress, windowStart);
        List<LoginThrottlePolicy.Attempt> policyAttempts =
                attempts.stream().map(LoginAttempt::toPolicyAttempt).toList();
        return throttlePolicy.decide(
                identifier,
                ipAddress,
                user == null ? null : user.getLockedUntil(),
                policyAttempts,
                clock);
    }

    private String randomPassword() {
        return UUID.randomUUID().toString();
    }

    /** Raised when the credentials are wrong. The message never says which part was wrong. */
    public static class InvalidCredentialsException extends BusinessException {

        private static final long serialVersionUID = 1L;

        /** Creates the error. */
        public InvalidCredentialsException() {
            super(ErrorCodes.INVALID_CREDENTIALS, 401, GENERIC_LOGIN_ERROR);
        }
    }

    /** Raised when the account is locked. */
    public static class LoginLockedException extends BusinessException {

        private static final long serialVersionUID = 1L;

        private final Instant retryAfter;

        /**
         * Creates the error.
         *
         * @param retryAfter when the caller may try again
         */
        public LoginLockedException(Instant retryAfter) {
            super(ErrorCodes.ACCOUNT_LOCKED, 423, "This account is locked. Try again later.");
            this.retryAfter = retryAfter;
        }

        /**
         * When the caller may try again.
         *
         * @return the moment
         */
        public Instant getRetryAfter() {
            return retryAfter;
        }
    }

    /** Raised when too many attempts came from one address. */
    public static class TooManyRequestsException extends BusinessException {

        private static final long serialVersionUID = 1L;

        private final Instant retryAfter;

        /**
         * Creates the error.
         *
         * @param retryAfter when the caller may try again
         */
        public TooManyRequestsException(Instant retryAfter) {
            super(ErrorCodes.TOO_MANY_REQUESTS, 429, "Too many login attempts. Try again later.");
            this.retryAfter = retryAfter;
        }

        /**
         * When the caller may try again.
         *
         * @return the moment
         */
        public Instant getRetryAfter() {
            return retryAfter;
        }
    }

    /** Raised when the password does not follow the password policy. */
    public static class PasswordPolicyException extends BusinessException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the error.
         *
         * @param message why the password was refused
         */
        public PasswordPolicyException(String message) {
            super(ErrorCodes.PASSWORD_POLICY, 422, message);
        }
    }
}
