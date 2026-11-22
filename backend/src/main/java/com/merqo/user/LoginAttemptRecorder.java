package com.merqo.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;

/**
 * Writes the login attempts and the lock counter.
 *
 * <p>Responsibility: remember every sign in attempt, so the throttle can look at the recent
 * history, and keep the counter of the account that failed.
 *
 * <p><b>Why every write runs in a transaction of its own.</b> A failed sign in ends with an
 * exception, and the transaction of the sign in is rolled back when that exception leaves the
 * service. If the attempt were written in that same transaction, the row would be rolled back with
 * it, and the throttle would count nothing: every account would stay open forever. The rows are
 * therefore written with {@link Propagation#REQUIRES_NEW}, so they are committed even when the sign
 * in itself fails. That is the whole point of the throttle: a refused sign in must still be
 * remembered.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - this class owns the throttle table and the counter. The
 *       service that checks the credentials only asks it to remember an attempt.
 *   <li><b>Composition</b> - the audit row is written here, so the attempt and its audit row are
 *       committed together.
 *   <li><b>Dependency injection</b> - the repositories and the audit log are injected.
 * </ul>
 */
@Service
public class LoginAttemptRecorder {

    private final LoginAttemptRepository attemptRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;

    /**
     * Creates the recorder.
     *
     * @param attemptRepository the login attempts
     * @param userRepository the accounts
     * @param auditService the audit log
     */
    public LoginAttemptRecorder(
            LoginAttemptRepository attemptRepository,
            AppUserRepository userRepository,
            AuditService auditService) {
        this.attemptRepository = attemptRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /**
     * The attempts the throttle has to look at.
     *
     * @param identifier the login name or email that was typed, lowercased
     * @param ipAddress the address the request came from, may be {@code null}
     * @param since the start of the window
     * @return the attempts
     */
    @Transactional(readOnly = true)
    public List<LoginAttempt> findForThrottleSince(
            String identifier, String ipAddress, Instant since) {
        return attemptRepository.findForThrottleSince(
                identifier, ipAddress == null ? "" : ipAddress, since);
    }

    /**
     * Remembers a failed attempt.
     *
     * @param identifier the login name or email that was typed, lowercased
     * @param ipAddress the address the request came from
     * @param now when the attempt happened
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String identifier, String ipAddress, Instant now) {
        attemptRepository.save(
                new LoginAttempt(UUID.randomUUID(), identifier, ipAddress, false, now));
    }

    /**
     * Remembers a successful attempt.
     *
     * @param identifier the login name or email that was typed, lowercased
     * @param ipAddress the address the request came from
     * @param now when the attempt happened
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String identifier, String ipAddress, Instant now) {
        attemptRepository.save(
                new LoginAttempt(UUID.randomUUID(), identifier, ipAddress, true, now));
    }

    /**
     * Counts one more failure of an account and locks it when the limit is reached.
     *
     * <p>The counter is read again inside this transaction, so the number is right even when two
     * sign ins fail at the same moment.
     *
     * @param user the account that failed
     * @param now when the attempt happened
     * @param ipAddress the address the request came from
     * @param correlationId the correlation id of the request
     * @return {@code true} when this attempt locked the account
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registerAccountFailure(
            AppUser user, Instant now, String ipAddress, String correlationId) {
        AppUser current = userRepository.findById(user.getId()).orElse(null);
        if (current == null) {
            return false;
        }

        int failures = current.getFailedLoginCount() + 1;
        current.setFailedLoginCount(failures);
        boolean locked = false;
        if (failures >= LoginThrottlePolicy.ACCOUNT_FAILURE_LIMIT) {
            current.setLockedUntil(now.plus(LoginThrottlePolicy.LOCK_DURATION));
            locked = true;
        }
        userRepository.save(current);

        auditService.record(
                new AuditEvent.Builder(AuditAction.LOGIN_FAILED, "app_users")
                        .entityId(current.getId())
                        .actor(current.getId(), current.getUsername())
                        .ipAddress(ipAddress)
                        .correlationId(correlationId)
                        .build());

        if (locked) {
            // The attempt that reaches the limit is also the attempt that locks the account, so
            // the audit log carries both rows: the failure and the lock it caused.
            auditService.record(
                    new AuditEvent.Builder(AuditAction.LOGIN_LOCKED, "app_users")
                            .entityId(current.getId())
                            .actor(current.getId(), current.getUsername())
                            .ipAddress(ipAddress)
                            .correlationId(correlationId)
                            .build());
        }

        return locked;
    }

    /**
     * Locks an account until a moment in time.
     *
     * @param user the account that is locked
     * @param lockedUntil when the lock ends
     * @param ipAddress the address the request came from
     * @param correlationId the correlation id of the request
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void lockAccount(
            AppUser user, Instant lockedUntil, String ipAddress, String correlationId) {
        AppUser current = userRepository.findById(user.getId()).orElse(null);
        if (current == null) {
            return;
        }
        current.setLockedUntil(lockedUntil);
        current.setFailedLoginCount(
                Math.max(current.getFailedLoginCount(), LoginThrottlePolicy.ACCOUNT_FAILURE_LIMIT));
        userRepository.save(current);

        auditService.record(
                new AuditEvent.Builder(AuditAction.LOGIN_LOCKED, "app_users")
                        .entityId(current.getId())
                        .actor(current.getId(), current.getUsername())
                        .ipAddress(ipAddress)
                        .correlationId(correlationId)
                        .build());
    }
}
