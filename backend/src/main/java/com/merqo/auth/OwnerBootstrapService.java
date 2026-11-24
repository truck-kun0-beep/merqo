package com.merqo.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.merqo.access.AccountType;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;
import com.merqo.user.PasswordPolicy;
import com.merqo.user.Username;

/**
 * Creates the first owner account from the environment.
 *
 * <p>Responsibility: make sure that a fresh database gets exactly one owner, and that a second
 * start does nothing.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>The owner is created when {@code INITIAL_OWNER_USERNAME} and {@code INITIAL_OWNER_PASSWORD}
 *       are set and no owner exists yet.
 *   <li>It is idempotent: starting the application twice creates one owner, not two. The partial
 *       unique index on the account type is the guard that makes it safe when two instances start
 *       at the same moment.
 *   <li>When the variables are missing, the application still starts, logs a clear warning and
 *       reports {@code setupRequired: true}. There is <b>no</b> public setup endpoint, because
 *       anybody could claim the system through it.
 *   <li>The password is never logged.
 * </ul>
 *
 * <p><b>Why this class uses a {@link TransactionTemplate} instead of {@code @Transactional}.</b>
 * Two instances may start at the same moment. One of them wins the race and writes the owner; the
 * other one is refused by the unique index. The loser then has to find out who the winner was. That
 * lookup must run in a <b>new</b> transaction, because the transaction of the failed insert is
 * already poisoned: PostgreSQL has aborted it and every further statement in it fails with "the
 * current transaction is aborted". A method that is annotated with {@code @Transactional} cannot do
 * that, because the recovery lookup would join the broken transaction. The three small transactions
 * below are therefore written out explicitly.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Idempotent operation</b> - running the operation twice gives the same result as running
 *       it once.
 *   <li><b>Dependency injection</b> - the clock, the encoder and the transaction manager are
 *       injected.
 *   <li><b>Observer</b> - the service reacts to the application ready event.
 *   <li><b>Fail fast and recover</b> - the race is not hidden: the loser says in the log that
 *       another instance was faster.
 * </ul>
 */
@Service
public class OwnerBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrapService.class);

    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    private final String initialOwnerUsername;
    private final String initialOwnerEmail;
    private final String initialOwnerPassword;
    private final boolean resetOwnerPasswordOnStart;

    /**
     * Creates the service.
     *
     * @param userRepository the accounts
     * @param auditService the audit log
     * @param passwordPolicy the password rules
     * @param passwordEncoder the BCrypt encoder
     * @param clock the clock
     * @param transactionManager the transaction manager
     * @param initialOwnerUsername the username of the first owner
     * @param initialOwnerEmail the email of the first owner, may be empty
     * @param initialOwnerPassword the password of the first owner, may be empty
     * @param resetOwnerPasswordOnStart whether the owner password is reset on every start
     */
    public OwnerBootstrapService(
            AppUserRepository userRepository,
            AuditService auditService,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${merqo.initial-owner-username:owner}") String initialOwnerUsername,
            @Value("${merqo.initial-owner-email:}") String initialOwnerEmail,
            @Value("${merqo.initial-owner-password:}") String initialOwnerPassword,
            @Value("${merqo.reset-owner-password-on-start:false}")
                    boolean resetOwnerPasswordOnStart) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        // The recovery lookup after a lost race must not join the broken transaction of the failed
        // insert, so it always runs in a transaction of its own.
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.initialOwnerUsername = initialOwnerUsername;
        this.initialOwnerEmail = initialOwnerEmail;
        this.initialOwnerPassword = initialOwnerPassword;
        this.resetOwnerPasswordOnStart = resetOwnerPasswordOnStart;
    }

    /**
     * Runs the bootstrap when the application is ready.
     *
     * @param event the ready event
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        try {
            bootstrapOwner();
        } catch (RuntimeException exception) {
            log.error("The first owner could not be created: {}", exception.getMessage());
        }
    }

    /**
     * Creates the owner when it does not exist yet.
     *
     * @return the owner, or {@code null} when there is no owner and no configuration for one
     */
    public AppUser bootstrapOwner() {
        AppUser existing = findOwner();
        if (existing != null) {
            return resetOwnerPasswordIfRequested(existing);
        }

        String username = Username.normalize(initialOwnerUsername);
        if (username == null
                || username.isBlank()
                || initialOwnerPassword == null
                || initialOwnerPassword.isBlank()) {
            log.warn(
                    "No owner account exists and INITIAL_OWNER_USERNAME or INITIAL_OWNER_PASSWORD is not"
                            + " set. The application is running in setup mode. See TROUBLESHOOTING.md.");
            return null;
        }

        return createOwnerIfAbsent(username, initialOwnerPassword);
    }

    /**
     * Creates the owner account when nobody else has created it yet.
     *
     * <p>This is the part of the bootstrap that can lose a race, so it is public and can be called
     * from a test with two threads at the same moment.
     *
     * @param rawUsername the username of the owner
     * @param rawPassword the password of the owner
     * @return the owner, either the one this call wrote or the one another call wrote first
     */
    public AppUser createOwnerIfAbsent(String rawUsername, String rawPassword) {
        String username = Username.normalize(rawUsername);
        if (username == null || !Username.isValid(username)) {
            log.error(
                    "The username of the first owner is not valid, so the owner was not created."
                            + " A username has 3 to 40 characters of a-z, 0-9, dot, underscore or hyphen.");
            return null;
        }
        PasswordPolicy.Result result = passwordPolicy.validateForOwner(rawPassword, username);
        if (!result.valid()) {
            log.error(
                    "The password of the first owner does not follow the password policy, so the owner"
                            + " was not created: {}. The owner password needs at least {} characters.",
                    result.message(),
                    PasswordPolicy.OWNER_MINIMUM_LENGTH);
            return null;
        }

        try {
            return writeOwner(username, rawPassword);
        } catch (DataIntegrityViolationException exception) {
            // Two instances may have started at the same moment. The unique index is the guard and
            // one of them loses, which is the correct outcome. The lookup runs in a new
            // transaction, because the transaction of the failed insert is already aborted.
            AppUser winner = findOwner();
            if (winner != null) {
                log.info(
                        "Another instance created the owner first. This instance continues without"
                                + " changing anything.");
                return winner;
            }
            throw exception;
        }
    }

    /**
     * Writes the owner account and its audit row in one transaction.
     *
     * @param username the username of the owner
     * @param rawPassword the password of the owner
     * @return the new owner
     */
    private AppUser writeOwner(String username, String rawPassword) {
        return writeTransaction.execute(
                status -> {
                    Instant now = Instant.now(clock);
                    AppUser owner =
                            new AppUser(
                                    UUID.randomUUID(),
                                    username,
                                    blankToNull(initialOwnerEmail),
                                    "Owner",
                                    passwordEncoder.encode(rawPassword),
                                    AccountType.OWNER,
                                    now);
                    owner.setMustChangePassword(true);
                    userRepository.saveAndFlush(owner);

                    auditService.record(
                            new AuditEvent.Builder(AuditAction.OWNER_BOOTSTRAPPED, "app_users")
                                    .entityId(owner.getId())
                                    .actor(owner.getId(), owner.getUsername())
                                    .added("username", owner.getUsername())
                                    .build());
                    return owner;
                });
    }

    /**
     * The owner account, if there is one. The lookup runs in a transaction of its own.
     *
     * @return the owner, or {@code null}
     */
    public AppUser findOwner() {
        return readTransaction.execute(
                status -> userRepository.findFirstByAccountType(AccountType.OWNER).orElse(null));
    }

    /**
     * Resets the owner password when the reset flag is on.
     *
     * @param owner the owner
     * @return the owner
     */
    public AppUser resetOwnerPasswordIfRequested(AppUser owner) {
        if (!resetOwnerPasswordOnStart) {
            return owner;
        }
        if (initialOwnerPassword == null || initialOwnerPassword.isBlank()) {
            log.warn(
                    "RESET_OWNER_PASSWORD_ON_START is true but INITIAL_OWNER_PASSWORD is not set. The"
                            + " owner password was not reset.");
            return owner;
        }
        PasswordPolicy.Result result =
                passwordPolicy.validateForOwner(initialOwnerPassword, owner.getUsername());
        if (!result.valid()) {
            log.error(
                    "The password of the owner does not follow the password policy, so it was not"
                            + " reset: {}",
                    result.message());
            return owner;
        }
        writeTransaction.executeWithoutResult(
                status -> {
                    AppUser current = userRepository.findById(owner.getId()).orElse(null);
                    if (current == null) {
                        return;
                    }
                    current.setPasswordHash(passwordEncoder.encode(initialOwnerPassword));
                    current.setMustChangePassword(true);
                    current.setUpdatedAt(Instant.now(clock));
                    userRepository.save(current);
                    auditService.record(
                            new AuditEvent.Builder(
                                            AuditAction.OWNER_PASSWORD_RESET_BY_ENV, "app_users")
                                    .entityId(current.getId())
                                    .actor(current.getId(), current.getUsername())
                                    .build());
                });
        log.warn(
                "WARNING: the owner password was reset from the environment. Remove"
                        + " RESET_OWNER_PASSWORD_ON_START from the deployment now. See"
                        + " TROUBLESHOOTING.md.");
        return owner;
    }

    /**
     * Whether the system still needs an owner.
     *
     * @return {@code true} when no owner exists
     */
    public boolean setupRequired() {
        return userRepository.countByAccountType(AccountType.OWNER) == 0;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
