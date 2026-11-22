package com.merqo.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The database access for the audit log.
 *
 * <p>Responsibility: read and write the {@code audit_logs} table.
 *
 * <p><b>This interface deliberately has no update and no delete method.</b> The audit log is append
 * only. A database trigger raises an error on any UPDATE or DELETE, so even a bug in the
 * application cannot rewrite history.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface</b> - the contract the service depends on.
 *   <li><b>Encapsulation</b> - the append only rule is enforced by the shape of the interface, not
 *       only by a convention.
 * </ul>
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Finds the audit rows the caller may see.
     *
     * <p>The owner passes an empty location set and sees everything. Everybody else passes the
     * locations they may read, and only rows whose location lies inside that set are returned.
     *
     * <p>A row without a location is a company wide event, such as a sign in. It is not tied to one
     * branch, so it belongs to everybody who may read the audit log and it is not hidden because it
     * has no location to compare.
     *
     * <p><b>Why the filters are written with {@code coalesce} and not with {@code :filter is
     * null}.</b> A parameter that only appears in a null check has no type of its own, so Hibernate
     * sends it as an untyped null and PostgreSQL cannot tell what it is. The database then refuses
     * the whole query. Putting the parameter next to the column it filters gives it that type, and
     * the meaning stays the same: an absent filter does not narrow the result.
     *
     * <p>The two columns that may be empty, the actor and the location, are compared with {@code is
     * not distinct from}. A plain equality would drop every row whose column is empty when no
     * filter is given, and those rows are exactly the company wide events such as a sign in.
     *
     * @param actorUserId the actor filter, may be {@code null}
     * @param action the action filter, may be {@code null}
     * @param entityType the entity type filter, may be {@code null}
     * @param locationId the location filter, may be {@code null}
     * @param from the start of the date range, may be {@code null}
     * @param to the end of the date range, may be {@code null}
     * @param locationIds the locations the caller may see
     * @param allLocations whether the caller may see every location
     * @param pageable the paging
     * @return the page of audit rows
     */
    @Query(
            """
            select a from AuditLog a
            where (:allLocations = true
                   or a.locationId is null
                   or a.locationId in :locationIds)
              and coalesce(:actorUserId, a.actorUserId) is not distinct from a.actorUserId
              and coalesce(:action, a.action) = a.action
              and coalesce(:entityType, a.entityType) = a.entityType
              and coalesce(:locationId, a.locationId) is not distinct from a.locationId
              and coalesce(:from, a.occurredAt) <= a.occurredAt
              and coalesce(:to, a.occurredAt) >= a.occurredAt
            """)
    Page<AuditLog> search(
            @Param("actorUserId") UUID actorUserId,
            @Param("action") String action,
            @Param("entityType") String entityType,
            @Param("locationId") UUID locationId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("locationIds") List<UUID> locationIds,
            @Param("allLocations") boolean allLocations,
            Pageable pageable);

    /**
     * Counts the audit rows of one action.
     *
     * @param action the action
     * @return the number of rows
     */
    long countByAction(String action);

    /**
     * Finds the audit rows of one record.
     *
     * @param entityType the entity type
     * @param entityId the entity id
     * @return the rows
     */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(
            String entityType, String entityId);
}
