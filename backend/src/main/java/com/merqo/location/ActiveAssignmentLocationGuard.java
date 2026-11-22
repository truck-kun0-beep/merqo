package com.merqo.location;

import org.springframework.stereotype.Component;

import com.merqo.access.LocationAssignmentRepository;

/**
 * Refuses to deactivate a location that still has people assigned to it.
 *
 * <p>Responsibility: one veto on the deactivation of a location. A branch with an active manager or
 * with staff still working there may not be closed.
 *
 * <p>OOP concept: Strategy. The location service asks every guard the same question and takes the
 * first "no".
 */
@Component
public class ActiveAssignmentLocationGuard implements LocationDeactivationGuard {

    private final LocationAssignmentRepository assignmentRepository;

    /**
     * Creates the guard.
     *
     * @param assignmentRepository the assignments
     */
    public ActiveAssignmentLocationGuard(LocationAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    @Override
    public GuardResult check(Location location) {
        if (!assignmentRepository.findByLocationIdAndEndedAtIsNull(location.getId()).isEmpty()) {
            return GuardResult.refuse(
                    "People are still assigned to this location. End those assignments first.");
        }
        return GuardResult.allow();
    }
}
