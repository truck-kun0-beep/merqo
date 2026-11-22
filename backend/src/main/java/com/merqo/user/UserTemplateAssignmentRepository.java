package com.merqo.user;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The database access for the templates a user holds.
 *
 * <p>Responsibility: read and write the {@code user_templates} table.
 *
 * <p>OOP concept: the interface is the contract the service depends on.
 */
public interface UserTemplateAssignmentRepository
        extends JpaRepository<UserTemplateAssignment, UserTemplateAssignmentId> {

    /**
     * Finds the template ids a user holds.
     *
     * @param userId the user
     * @return the template ids
     */
    @Query("select ut.templateId from UserTemplateAssignment ut where ut.userId = :userId")
    List<UUID> findTemplateIdsByUserId(@Param("userId") UUID userId);

    /**
     * Finds the template links of a user.
     *
     * @param userId the user
     * @return the links
     */
    List<UserTemplateAssignment> findByUserId(UUID userId);

    /**
     * Removes every template link of one user.
     *
     * @param userId the user
     */
    @Modifying
    @Query("delete from UserTemplateAssignment ut where ut.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
