package com.merqo.access;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One permission that one manager may delegate to a staff member.
 *
 * <p>Responsibility: hold one entry of a delegation list.
 *
 * <p>OOP concept: mapping a many to many relation with its own entity. The two columns together
 * identify the row, so the key is a separate {@link ManagerDelegationId} value object.
 */
@Entity
@Table(name = "manager_delegations")
@IdClass(ManagerDelegationId.class)
public class ManagerDelegation {

    @Id
    @Column(name = "manager_user_id")
    private UUID managerUserId;

    @Id
    @Column(name = "permission", length = 60)
    private String permission;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ManagerDelegation() {}

    /**
     * Creates the entry.
     *
     * @param managerUserId the manager
     * @param permission the permission code
     * @param createdAt when it was created
     */
    public ManagerDelegation(UUID managerUserId, String permission, Instant createdAt) {
        this.managerUserId = managerUserId;
        this.permission = permission;
        this.createdAt = createdAt;
    }

    /**
     * The manager.
     *
     * @return the manager id
     */
    public UUID getManagerUserId() {
        return managerUserId;
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
     * When the entry was created.
     *
     * @return the creation moment
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
