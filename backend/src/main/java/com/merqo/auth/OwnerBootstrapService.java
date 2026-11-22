package com.merqo.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.access.AccountType;
import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.common.config.MerqoModeProperties;
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
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Idempotent operation</b> - running the operation twice gives the same result as running
 *       it once.
 *   <li><b>Dependency injection</b> - the clock and the repositories are injected.
 *   <li><b>Observer</b> - the service reacts to the application ready event.
 * </ul>
 */
@Service
public class OwnerBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrapService.class);

    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final PasswordPolicy passwordPolicy;
    private final MerqoModeProperties modeProperties;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

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
     * @param modeProperties the mode flags
     * @param passwordEncoder the BCrypt encoder
     * @param clock the clock
     * @param initialOwnerUsername the username of the first owner
     * @param initialOwnerEmail the email of the first owner, may be empty
     * @param initialOwnerPassword the password of the first owner, may be empty
     * @param resetOwnerPasswordOnStart whether the owner password is reset on every start
     */
    public OwnerBootstrapService(
            AppUserRepository userRepository,
            AuditService auditService,
            PasswordPolicy passwordPolicy,
            MerqoModeProperties modeProperties,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${merqo.initial-owner-username:owner}") String initialOwnerUsername,
            @Value("${merqo.initial-owner-email:}") String initialOwnerEmail,
            @Value("${merqo.initial-owner-password:}") String initialOwnerPassword,
            @Value("${merqo.reset-owner-password-on-start:false}")
                    boolean resetOwnerPasswordOnStart) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.passwordPolicy = passwordPolicy;
        this.modeProperties = modeProperties;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
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
    @Transactional
    public AppUser bootstrapOwner() {
        AppUser existing = userRepository.findFirstByAccountType(AccountType.OWNER).orElse(null);
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
        if (!Username.isValid(username)) {
            log.error(
                    "INITIAL_OWNER_USERNAME is not a valid username. The owner was not created."
                            + " A username has 3 to 40 characters of a-z, 0-9, dot, underscore or hyphen.");
            return null;
        }

        PasswordPolicy.Result result =
                passwordPolicy.validateForOwner(initialOwnerPassword, username);
        if (!result.valid()) {
            log.error(
                    "The password of the first owner does not follow the password policy, so the owner"
                            + " was not created: {}. The owner password needs at least {} characters.",
                    result.message(),
                    PasswordPolicy.OWNER_MINIMUM_LENGTH);
            return null;
        }

        Instant now = Instant.now(clock);
        AppUser owner =
                new AppUser(
                        UUID.randomUUID(),
                        username,
                        blankToNull(initialOwnerEmail),
                        "Owner",
                        passwordEncoderHash(initialOwnerPassword),
                        AccountType.OWNER,
                        now);
        owner.setMustChangePassword(true);
        try {
            userRepository.saveAndFlush(owner);
        } catch (RuntimeException exception) {
            // Two instances may have started at the same moment. The unique index is the guard and
            // one of them loses, which is the correct outcome.
            AppUser winner = userRepository.findFirstByAccountType(AccountType.OWNER).orElse(null);
            if (winner != null) {
                log.info(
                        "Another instance created the owner first. This instance continues without"
                                + " changing anything.");
                return winner;
            }
            throw exception;
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.OWNER_BOOTSTRAPPED, "app_users")
                        .entityId(owner.getId())
                        .actor(owner.getId(), owner.getUsername())
                        .added("username", owner.getUsername())
                        .build());

        log.info(
                "The first owner account was created. The owner must change the password at the"
                        + " first sign in.");
        return owner;
    }

    /**
     * Resets the owner password when the reset flag is on.
     *
     * @param owner the owner
     * @return the owner
     */
    @Transactional
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
        owner.setPasswordHash(passwordEncoderHash(initialOwnerPassword));
        owner.setMustChangePassword(true);
        owner.setUpdatedAt(Instant.now(clock));
        userRepository.save(owner);
        auditService.record(
                new AuditEvent.Builder(AuditAction.OWNER_PASSWORD_RESET_BY_ENV, "app_users")
                        .entityId(owner.getId())
                        .actor(owner.getId(), owner.getUsername())
                        .build());
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
    @Transactional(readOnly = true)
    public boolean setupRequired() {
        return userRepository.countByAccountType(AccountType.OWNER) == 0;
    }

    private String passwordEncoderHash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
