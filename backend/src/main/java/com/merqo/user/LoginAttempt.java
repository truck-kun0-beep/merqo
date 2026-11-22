package com.merqo.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One recorded login attempt.
 *
 * <p>Responsibility: hold one attempt, so that the throttle policy can look at the recent history.
 *
 * <p>This is operational data, not business data. It is deleted after 24 hours by a scheduled job.
 *
 * <p>OOP concept: mapping. The class is deliberately thin, because the interesting part is the
 * {@link LoginThrottlePolicy}, which is plain Java.
 */
@Entity
@Table(name = "login_attempts")
public class LoginAttempt {

    @Id private UUID id;

    @Column(name = "identifier", nullable = false, length = 190)
    private String identifier;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    /** Required by JPA. */
    protected LoginAttempt() {}

    /**
     * Creates an attempt.
     *
     * @param id the id
     * @param identifier the login name or email that was typed, lowercased
     * @param ipAddress the address the request came from
     * @param success whether the attempt succeeded
     * @param attemptedAt when the attempt happened
     */
    public LoginAttempt(
            UUID id, String identifier, String ipAddress, boolean success, Instant attemptedAt) {
        this.id = id;
        this.identifier = identifier == null ? "" : identifier.toLowerCase(java.util.Locale.ROOT);
        this.ipAddress = ipAddress;
        this.success = success;
        this.attemptedAt = attemptedAt;
    }

    /**
     * The id of the attempt.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The identifier that was typed.
     *
     * @return the identifier
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * The address the request came from.
     *
     * @return the address
     */
    public String getIpAddress() {
        return ipAddress;
    }

    /**
     * Whether the attempt succeeded.
     *
     * @return {@code true} when it succeeded
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * When the attempt happened.
     *
     * @return the moment
     */
    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    /**
     * The attempt as a value object for the throttle policy.
     *
     * @return the attempt
     */
    public LoginThrottlePolicy.Attempt toPolicyAttempt() {
        return new LoginThrottlePolicy.Attempt(identifier, ipAddress, success, attemptedAt);
    }
}
