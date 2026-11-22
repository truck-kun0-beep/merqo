package com.merqo.access;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The database access for the delegation lists of the managers.
 *
 * <p>Responsibility: read and write the {@code manager_delegations} table. Only the owner may
 * change these rows.
 *
 * <p>OOP concept: a many to many relation mapped with its own entity.
 */
public interface ManagerDelegationRepository
        extends JpaRepository<ManagerDelegation, ManagerDelegationId> {

    /**
     * Finds the permissions a manager may delegate.
     *
     * <p>The query is written out, because Spring Data cannot derive a query that returns a column
     * instead of the whole row.
     *
     * @param managerUserId the manager
     * @return the permission codes
     */
    @Query("select d.permission from ManagerDelegation d where d.managerUserId = :managerUserId")
    List<String> findPermissionCodesByManagerUserId(@Param("managerUserId") UUID managerUserId);

    /**
     * Finds the delegation rows of one manager.
     *
     * @param managerUserId the manager
     * @return the rows
     */
    List<ManagerDelegation> findByManagerUserId(UUID managerUserId);

    /**
     * Removes every delegation row of one manager.
     *
     * @param managerUserId the manager
     */
    @Modifying
    @Query("delete from ManagerDelegation d where d.managerUserId = :managerUserId")
    void deleteByManagerUserId(@Param("managerUserId") UUID managerUserId);
}
