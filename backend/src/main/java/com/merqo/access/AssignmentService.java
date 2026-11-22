package com.merqo.access;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.audit.AuditAction;
import com.merqo.audit.AuditEvent;
import com.merqo.audit.AuditService;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.NotFoundException;
import com.merqo.common.error.ValidationException;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.user.AppUser;
import com.merqo.user.AppUserRepository;

/**
 * The rules of the assignments: who works where.
 *
 * <p>Responsibility: create and end the assignments, and appoint or replace the manager of a
 * branch.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>Only the owner appoints a manager ({@code MANAGER_MANAGE}).
 *   <li>One active manager per branch. The rule is enforced twice: in Java by {@link
 *       AssignmentRules} and in the database by a partial unique index. The Java check gives the
 *       caller a clear message; the index is the guard that holds when two requests arrive at the
 *       same moment.
 *   <li>A warehouse accepts only staff assignments.
 *   <li>An assignment is ended, never deleted, so the history stays readable.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Delegation</b> - the combination rules live in {@link AssignmentRules}, which is plain
 *       Java.
 *   <li><b>Transaction</b> - replacing a manager ends the old assignment and creates the new one in
 *       one transaction, so a branch is never without a manager because of a half finished request.
 *   <li><b>Single responsibility</b> - the assignment rules live here, not in the user service.
 * </ul>
 */
@Service
public class AssignmentService {

    private final LocationAssignmentRepository assignmentRepository;
    private final LocationRepository locationRepository;
    private final AppUserRepository userRepository;
    private final AssignmentRules rules;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param assignmentRepository the assignments
     * @param locationRepository the locations
     * @param userRepository the accounts
     * @param rules the combination rules
     * @param auditService the audit log
     * @param clock the clock
     */
    public AssignmentService(
            LocationAssignmentRepository assignmentRepository,
            LocationRepository locationRepository,
            AppUserRepository userRepository,
            AssignmentRules rules,
            AuditService auditService,
            Clock clock) {
        this.assignmentRepository = assignmentRepository;
        this.locationRepository = locationRepository;
        this.userRepository = userRepository;
        this.rules = rules;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * The active assignments of a location.
     *
     * @param locationId the location
     * @return the assignments
     */
    @Transactional(readOnly = true)
    public List<LocationAssignment> list(UUID locationId) {
        return assignmentRepository.findByLocationIdAndEndedAtIsNull(locationId);
    }

    /**
     * The active assignments of a user.
     *
     * @param userId the user
     * @return the assignments
     */
    @Transactional(readOnly = true)
    public List<LocationAssignment> listForUser(UUID userId) {
        return assignmentRepository.findByUserIdAndEndedAtIsNull(userId);
    }

    /**
     * Creates an assignment.
     *
     * @param userId the user
     * @param locationId the location
     * @param assignmentType the kind of assignment
     * @param replaceExisting whether the current manager may be replaced
     * @param actorUserId who made the assignment
     * @param actorUsername the username of that user
     * @return the new assignment
     */
    @Transactional
    public LocationAssignment create(
            UUID userId,
            UUID locationId,
            AssignmentType assignmentType,
            boolean replaceExisting,
            UUID actorUserId,
            String actorUsername) {
        AppUser user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        Location location =
                locationRepository
                        .findById(locationId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));

        Optional<LocationAssignment> existingManager =
                assignmentRepository.findFirstByLocationIdAndAssignmentTypeAndEndedAtIsNull(
                        locationId, AssignmentType.MANAGER);

        AssignmentRules.Decision decision =
                rules.check(
                        new AssignmentRules.Request(
                                userId,
                                user.getAccountType(),
                                locationId,
                                location.getLocationType(),
                                assignmentType,
                                existingManager.map(LocationAssignment::getUserId).orElse(null),
                                replaceExisting));
        if (!decision.allowed()) {
            throw new ForbiddenException(
                    decision.reason().getErrorCode(),
                    "This assignment is not allowed: "
                            + decision.reason()
                                    .name()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .replace('_', ' ')
                            + ".");
        }

        if (assignmentType == AssignmentType.MANAGER && existingManager.isPresent()) {
            if (replaceExisting) {
                LocationAssignment old = existingManager.get();
                old.end(Instant.now(clock));
                assignmentRepository.save(old);
                auditService.record(
                        new AuditEvent.Builder(AuditAction.MANAGER_REPLACED, "location_assignments")
                                .entityId(old.getId())
                                .locationId(locationId)
                                .actor(actorUserId, actorUsername)
                                .changed(
                                        "managerUserId",
                                        old.getUserId().toString(),
                                        userId.toString())
                                .build());
            } else {
                throw new ConflictException(
                        "PRIMARY_MANAGER_EXISTS",
                        "This location already has a manager. Use replaceExisting to replace them.");
            }
        }

        // A second active assignment of the same user at the same location is refused by the
        // database, so the existing one is ended first.
        assignmentRepository
                .findByUserIdAndLocationIdAndEndedAtIsNull(userId, locationId)
                .ifPresent(
                        existing -> {
                            existing.end(Instant.now(clock));
                            assignmentRepository.save(existing);
                        });

        LocationAssignment assignment =
                new LocationAssignment(
                        UUID.randomUUID(),
                        userId,
                        locationId,
                        assignmentType,
                        actorUserId,
                        Instant.now(clock));
        try {
            assignmentRepository.saveAndFlush(assignment);
        } catch (RuntimeException exception) {
            throw new ConflictException(
                    "CONFLICT",
                    "Another request changed this assignment at the same moment. Try again.");
        }

        auditService.record(
                new AuditEvent.Builder(AuditAction.ASSIGNMENT_CREATED, "location_assignments")
                        .entityId(assignment.getId())
                        .locationId(locationId)
                        .actor(actorUserId, actorUsername)
                        .added("userId", userId.toString())
                        .added("assignmentType", assignmentType.name())
                        .build());
        return assignment;
    }

    /**
     * Ends an assignment. The row stays in the table.
     *
     * @param assignmentId the assignment
     * @param actorUserId who ended it
     * @param actorUsername the username of that user
     * @return the ended assignment
     */
    @Transactional
    public LocationAssignment end(UUID assignmentId, UUID actorUserId, String actorUsername) {
        LocationAssignment assignment =
                assignmentRepository
                        .findById(assignmentId)
                        .orElseThrow(
                                () -> new NotFoundException("The requested record was not found."));
        if (!assignment.isActive()) {
            throw new ConflictException("CONFLICT", "This assignment has already ended.");
        }
        assignment.end(Instant.now(clock));
        assignmentRepository.save(assignment);

        auditService.record(
                new AuditEvent.Builder(AuditAction.ASSIGNMENT_ENDED, "location_assignments")
                        .entityId(assignment.getId())
                        .locationId(assignment.getLocationId())
                        .actor(actorUserId, actorUsername)
                        .changed("endedAt", null, assignment.getEndedAt().toString())
                        .build());
        return assignment;
    }

    /**
     * Whether an assignment type is allowed at a location type.
     *
     * @param assignmentType the kind of assignment
     * @param locationType the kind of location
     * @return {@code true} when the combination is allowed
     */
    public boolean isAllowed(AssignmentType assignmentType, LocationType locationType) {
        return assignmentType.isAllowedAt(locationType);
    }

    /**
     * Refuses a location that may not be used for this kind of assignment.
     *
     * @param location the location
     * @param assignmentType the kind of assignment
     */
    public void requireAllowed(Location location, AssignmentType assignmentType) {
        if (!isAllowed(assignmentType, location.getLocationType())) {
            throw new ValidationException(
                    "ASSIGNMENT_NOT_ALLOWED", "A warehouse accepts only staff assignments.");
        }
    }
}
