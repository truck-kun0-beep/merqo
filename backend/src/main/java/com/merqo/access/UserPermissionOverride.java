package com.merqo.access;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One ALLOW or DENY override of one permission for one user.
 *
 * <p>Responsibility: hold one override. The rule "a deny always beats an allow" lives in the
 * resolver, not here.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Composition</b> - the override holds the permission code as text, because the catalogue
 *       is the Java enum and the database only stores the code.
 * </ul>
 */
@Entity
@Table(name = "user_permission_overrides")
public class UserPermissionOverride {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "permission", nullable = false, length = 60)
    private String permission;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, length = 10)
    private PermissionResolver.OverrideEffect effect;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected UserPermissionOverride() {}

    /**
     * Creates an override.
     *
     * @param id the id
     * @param userId the user
     * @param permission the permission code
     * @param effect whether the permission is allowed or denied
     * @param locationId the location, or {@code null} for every location
     * @param createdBy who created the override
     * @param createdAt when it was created
     */
    public UserPermissionOverride(
            UUID id,
            UUID userId,
            String permission,
            PermissionResolver.OverrideEffect effect,
            UUID locationId,
            UUID createdBy,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.permission = permission;
        this.effect = effect;
        this.locationId = locationId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /**
     * The id of the override.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The user the override belongs to.
     *
     * @return the user id
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * The stored permission code.
     *
     * @return the code
     */
    public String getPermission() {
        return permission;
    }

    /**
     * Whether the permission is allowed or denied.
     *
     * @return the effect
     */
    public PermissionResolver.OverrideEffect getEffect() {
        return effect;
    }

    /**
     * The location the override is limited to.
     *
     * @return the location id, or {@code null}
     */
    public UUID getLocationId() {
        return locationId;
    }

    /**
     * Who created the override.
     *
     * @return the creator id
     */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /**
     * When the override was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
