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
 * One assignment: this user works at this location, as manager or as staff.
 *
 * <p>Responsibility: hold one link between a user and a location. An assignment is never deleted;
 * it is ended, so the history of who managed which branch stays readable.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Mapping</b> - the class describes how the object is stored.
 *   <li><b>Encapsulation</b> - {@link #isActive()} hides the meaning of {@code ended_at}.
 *   <li><b>Composition</b> - the assignment holds the two ids it links together instead of copying
 *       the two objects.
 * </ul>
 */
@Entity
@Table(name = "location_assignments")
public class LocationAssignment {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_type", nullable = false, length = 20)
    private AssignmentType assignmentType;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** Required by JPA. */
    protected LocationAssignment() {}

    /**
     * Creates an assignment.
     *
     * @param id the id
     * @param userId the user
     * @param locationId the location
     * @param assignmentType the kind of assignment
     * @param assignedBy who made the assignment
     * @param startedAt when the assignment started
     */
    public LocationAssignment(
            UUID id,
            UUID userId,
            UUID locationId,
            AssignmentType assignmentType,
            UUID assignedBy,
            Instant startedAt) {
        this.id = id;
        this.userId = userId;
        this.locationId = locationId;
        this.assignmentType = assignmentType;
        this.assignedBy = assignedBy;
        this.startedAt = startedAt;
    }

    /**
     * The id of the assignment.
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * The user who holds the assignment.
     *
     * @return the user id
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * The location of the assignment.
     *
     * @return the location id
     */
    public UUID getLocationId() {
        return locationId;
    }

    /**
     * The kind of assignment.
     *
     * @return the assignment type
     */
    public AssignmentType getAssignmentType() {
        return assignmentType;
    }

    /**
     * Who made the assignment.
     *
     * @return the id of the user who assigned it
     */
    public UUID getAssignedBy() {
        return assignedBy;
    }

    /**
     * When the assignment started.
     *
     * @return the start moment
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * When the assignment ended, or {@code null} when it is still active.
     *
     * @return the end moment
     */
    public Instant getEndedAt() {
        return endedAt;
    }

    /**
     * Ends the assignment. The row stays in the table.
     *
     * @param endedAt when it ended
     */
    public void end(Instant endedAt) {
        this.endedAt = endedAt;
    }

    /**
     * Whether the assignment is still running.
     *
     * @return {@code true} when the assignment is active
     */
    public boolean isActive() {
        return endedAt == null;
    }
}
