package com.merqo.access;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.common.error.ForbiddenException;
import com.merqo.common.error.NotFoundException;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.location.LocationService;
import com.merqo.user.CurrentUserService;

/**
 * The endpoints of the assignments.
 *
 * <p>Responsibility: translate HTTP into calls on {@link AssignmentService}.
 *
 * <p>Two rules are enforced here because they decide who is allowed to call the service at all:
 *
 * <ul>
 *   <li>Only the owner appoints a manager.
 *   <li>A manager may only appoint staff inside their own scope.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Thin controller</b> - the controller only maps the request and the response.
 *   <li><b>Delegation</b> - the scope check is done with the {@link AccessContext}.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Assignments", description = "Who works where")
public class AssignmentController {

    private final AssignmentService assignmentService;
    private final LocationAssignmentRepository assignmentRepository;
    private final LocationRepository locationRepository;
    private final LocationService locationService;
    private final CurrentUserService currentUserService;

    /**
     * Creates the controller.
     *
     * @param assignmentService the assignment rules
     * @param assignmentRepository the assignments
     * @param locationRepository the locations
     * @param locationService the location rules
     * @param currentUserService the signed in user
     */
    public AssignmentController(
            AssignmentService assignmentService,
            LocationAssignmentRepository assignmentRepository,
            LocationRepository locationRepository,
            LocationService locationService,
            CurrentUserService currentUserService) {
        this.assignmentService = assignmentService;
        this.assignmentRepository = assignmentRepository;
        this.locationRepository = locationRepository;
        this.locationService = locationService;
        this.currentUserService = currentUserService;
    }

    /**
     * The active assignments of a location.
     *
     * @param locationId the location
     * @return the assignments
     */
    @GetMapping("/locations/{locationId}/assignments")
    @Operation(summary = "The active assignments of a location")
    public List<Map<String, Object>> list(@PathVariable UUID locationId) {
        AccessContext context = currentUserService.requireAccessContext();
        Location location = requireVisibleLocation(context, locationId);
        List<Map<String, Object>> items = new ArrayList<>();
        assignmentService
                .list(location.getId())
                .forEach(
                        assignment -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("id", assignment.getId());
                            entry.put("userId", assignment.getUserId());
                            entry.put("locationId", assignment.getLocationId());
                            entry.put("assignmentType", assignment.getAssignmentType().name());
                            entry.put("startedAt", assignment.getStartedAt());
                            entry.put("endedAt", assignment.getEndedAt());
                            items.add(entry);
                        });
        return items;
    }

    /**
     * Creates an assignment.
     *
     * @param request the assignment to create
     * @return the new assignment
     */
    @PostMapping("/assignments")
    @Operation(summary = "Assign a user to a location")
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody CreateAssignmentRequest request) {
        AccessContext context = currentUserService.requireAccessContext();
        Location location = requireVisibleLocation(context, request.locationId());

        if (request.assignmentType() == AssignmentType.MANAGER) {
            if (!context.isOwner()) {
                throw new ForbiddenException(
                        "MANAGER_MANAGE", "Only the owner may appoint a manager.");
            }
        } else if (!context.isOwner() && !context.isInScope(location.getId())) {
            throw new NotFoundException("The requested record was not found.");
        }

        LocationAssignment assignment =
                assignmentService.create(
                        request.userId(),
                        location.getId(),
                        request.assignmentType(),
                        request.replaceExisting(),
                        context.getUserId(),
                        context.getUsername());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", assignment.getId());
        body.put("userId", assignment.getUserId());
        body.put("locationId", assignment.getLocationId());
        body.put("assignmentType", assignment.getAssignmentType().name());
        body.put("startedAt", assignment.getStartedAt());
        body.put("endedAt", assignment.getEndedAt());
        return ResponseEntity.status(201).body(body);
    }

    /**
     * Ends an assignment. The row stays in the table.
     *
     * @param id the assignment
     * @return the ended assignment
     */
    @PostMapping("/assignments/{id}/end")
    @Operation(summary = "End an assignment")
    public Map<String, Object> end(@PathVariable UUID id) {
        AccessContext context = currentUserService.requireAccessContext();
        LocationAssignment assignment = findAssignment(id);
        requireMayManageAssignment(context, assignment);
        LocationAssignment ended =
                assignmentService.end(id, context.getUserId(), context.getUsername());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", ended.getId());
        body.put("userId", ended.getUserId());
        body.put("locationId", ended.getLocationId());
        body.put("assignmentType", ended.getAssignmentType().name());
        body.put("startedAt", ended.getStartedAt());
        body.put("endedAt", ended.getEndedAt());
        return body;
    }

    private LocationAssignment findAssignment(UUID id) {
        return assignmentRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("The requested record was not found."));
    }

    private Location requireVisibleLocation(AccessContext context, UUID locationId) {
        return locationService.get(locationId, context.getScopeLocationIds(), context.isOwner());
    }

    private void requireMayManageAssignment(AccessContext context, LocationAssignment assignment) {
        if (context.isOwner()) {
            return;
        }
        if (!context.isInScope(assignment.getLocationId())) {
            throw new NotFoundException("The requested record was not found.");
        }
    }

    /**
     * The body of a create assignment request.
     *
     * @param userId the user
     * @param locationId the location
     * @param assignmentType the kind of assignment
     * @param replaceExisting whether the current manager may be replaced
     */
    public record CreateAssignmentRequest(
            @NotNull UUID userId,
            @NotNull UUID locationId,
            @NotNull AssignmentType assignmentType,
            boolean replaceExisting) {}
}
