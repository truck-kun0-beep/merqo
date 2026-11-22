package com.merqo.access;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The database access for the overrides of a user.
 *
 * <p>Responsibility: read and write the {@code user_permission_overrides} table.
 *
 * <p>OOP concept: the interface is the contract; the service that uses it does not know that Spring
 * Data implements it.
 */
public interface UserPermissionOverrideRepository
        extends JpaRepository<UserPermissionOverride, UUID> {

    /**
     * Finds every override of one user.
     *
     * @param userId the user
     * @return the overrides
     */
    List<UserPermissionOverride> findByUserId(UUID userId);

    /**
     * Removes every override of one user.
     *
     * @param userId the user
     */
    @Modifying
    @Query("delete from UserPermissionOverride o where o.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
