package com.merqo.access;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The key of a delegation entry: the manager and the permission.
 *
 * <p>Responsibility: name the two columns that together identify one row of {@code
 * manager_delegations}.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Value object</b> - the key has no state of its own beyond the two parts.
 *   <li><b>Immutability</b> - the components are final.
 * </ul>
 *
 * @param managerUserId the manager
 * @param permission the permission code
 */
public record ManagerDelegationId(UUID managerUserId, String permission) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The default constructor, needed by JPA. */
    public ManagerDelegationId() {
        this(null, null);
    }

    /**
     * Creates the key.
     *
     * @param managerUserId the manager
     * @param permission the permission code
     */
    public ManagerDelegationId {
        Objects.requireNonNull(managerUserId, "The manager id is required");
        Objects.requireNonNull(permission, "The permission is required");
    }
}
