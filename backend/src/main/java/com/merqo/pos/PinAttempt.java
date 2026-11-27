package com.merqo.pos;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Operational throttle record for a manager approval PIN attempt.
 *
 * <p>Responsibility: record whether an approval PIN attempt by a cashier at a location succeeded or
 * failed so repeated failures can trigger a temporary lockout.
 *
 * <p>OOP concept: <b>Mapping / Entity</b> — models the {@code pin_attempts} throttle table.
 */
@Entity
@Table(name = "pin_attempts")
public class PinAttempt {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    /** Required by JPA. */
    protected PinAttempt() {}

    public PinAttempt(UUID id, UUID userId, UUID locationId, boolean success, Instant attemptedAt) {
        this.id = id;
        this.userId = userId;
        this.locationId = locationId;
        this.success = success;
        this.attemptedAt = attemptedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public boolean isSuccess() {
        return success;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
