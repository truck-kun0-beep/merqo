package com.merqo.access;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The database access for assignments.
 *
 * <p>Responsibility: read and write the {@code location_assignments} table.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface</b> - the service depends on the idea, not on Spring Data.
 *   <li><b>Query as data</b> - the active assignments of a user are asked for with one query
 *       instead of loading all assignments and filtering them in memory.
 * </ul>
 */
public interface LocationAssignmentRepository extends JpaRepository<LocationAssignment, UUID> {

    /**
     * Finds the active assignments of one user.
     *
     * @param userId the user
     * @return the active assignments
     */
    List<LocationAssignment> findByUserIdAndEndedAtIsNull(UUID userId);

    /**
     * Finds the active assignments at one location.
     *
     * @param locationId the location
     * @return the active assignments
     */
    List<LocationAssignment> findByLocationIdAndEndedAtIsNull(UUID locationId);

    /**
     * Finds the active assignment of one user at one location.
     *
     * @param userId the user
     * @param locationId the location
     * @return the assignment, if there is one
     */
    Optional<LocationAssignment> findByUserIdAndLocationIdAndEndedAtIsNull(
            UUID userId, UUID locationId);

    /**
     * Finds the active manager of one location.
     *
     * @param locationId the location
     * @param assignmentType the kind of assignment
     * @return the assignment, if there is one
     */
    Optional<LocationAssignment> findFirstByLocationIdAndAssignmentTypeAndEndedAtIsNull(
            UUID locationId, AssignmentType assignmentType);

    /**
     * Counts the active assignments of one type at one location.
     *
     * @param locationId the location
     * @param assignmentType the type
     * @return the number of assignments
     */
    long countByLocationIdAndAssignmentTypeAndEndedAtIsNull(
            UUID locationId, AssignmentType assignmentType);

    /**
     * Finds the location ids a user is currently assigned to.
     *
     * @param userId the user
     * @return the location ids
     */
    @Query(
            "select a.locationId from LocationAssignment a "
                    + "where a.userId = :userId and a.endedAt is null")
    List<UUID> findActiveLocationIdsByUserId(@Param("userId") UUID userId);

    /**
     * Finds the user ids that are currently assigned to one location.
     *
     * @param locationId the location
     * @return the user ids
     */
    @Query(
            "select a.userId from LocationAssignment a "
                    + "where a.locationId = :locationId and a.endedAt is null")
    List<UUID> findActiveUserIdsByLocationId(@Param("locationId") UUID locationId);

    /**
     * Ends every active assignment of one user at one location.
     *
     * <p>The rows stay in the table, so the history is kept.
     *
     * @param userId the user
     * @param locationId the location
     * @param endedAt when the assignments ended
     * @return the number of ended assignments
     */
    @Modifying
    @Query(
            "update LocationAssignment a set a.endedAt = :endedAt "
                    + "where a.userId = :userId and a.locationId = :locationId and a.endedAt is null")
    int endActiveAssignments(
            @Param("userId") UUID userId,
            @Param("locationId") UUID locationId,
            @Param("endedAt") Instant endedAt);
}
