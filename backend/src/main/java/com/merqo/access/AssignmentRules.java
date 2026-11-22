package com.merqo.access;

import java.util.Set;
import java.util.UUID;

/**
 * Decides which account types may hold which assignment types on which location types.
 *
 * <p>Responsibility: answer the "may this combination exist?" question about assignments. The
 * database has a partial unique index for the one manager per branch rule, this class has the same
 * rule in plain Java so the API can refuse the request with a clear message instead of a database
 * error.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - one question, one class.
 *   <li><b>Encapsulation</b> - the rules are private methods; callers only see {@link #check}.
 *   <li><b>Defensive programming</b> - the class answers "no" when a fact is missing, so a bug can
 *       never open a hole in the access model.
 * </ul>
 */
public class AssignmentRules {

    /**
     * Checks one assignment.
     *
     * @param request the facts of the request
     * @return the decision
     */
    public Decision check(Request request) {
        if (request.accountType() == null) {
            return Decision.refused(Reason.UNKNOWN_ACCOUNT_TYPE);
        }
        if (!request.accountType().mayHoldManagerAssignments()
                && request.assignmentType() == AssignmentType.MANAGER) {
            return Decision.refused(Reason.ACCOUNT_TYPE_CANNOT_BE_MANAGER);
        }
        if (request.locationType() == null) {
            return Decision.refused(Reason.UNKNOWN_LOCATION_TYPE);
        }
        if (!request.assignmentType().isAllowedAt(request.locationType())) {
            return Decision.refused(Reason.WAREHOUSE_ACCEPTS_STAFF_ONLY);
        }
        if (request.existingActiveManagerUserId() != null
                && request.existingActiveManagerUserId().equals(request.userId())) {
            // The same person is already the active manager of this location.
            return Decision.refused(Reason.ALREADY_THE_ACTIVE_MANAGER);
        }
        if (request.existingActiveManagerUserId() != null && !request.replaceExisting()) {
            return Decision.refused(Reason.PRIMARY_MANAGER_EXISTS);
        }
        return Decision.permitted();
    }

    /**
     * The facts of one assignment request.
     *
     * @param userId the user to assign
     * @param accountType the account type of the user
     * @param locationId the location
     * @param locationType the type of the location
     * @param assignmentType the kind of assignment
     * @param existingActiveManagerUserId the current active manager of the location, or {@code
     *     null}
     * @param replaceExisting whether the current manager may be replaced
     */
    public record Request(
            UUID userId,
            AccountType accountType,
            UUID locationId,
            LocationType locationType,
            AssignmentType assignmentType,
            UUID existingActiveManagerUserId,
            boolean replaceExisting) {}

    /**
     * The answer of the rules.
     *
     * @param allowed whether the assignment may be made
     * @param reason why it was refused
     */
    public record Decision(boolean allowed, Reason reason) {

        /**
         * Creates a decision.
         *
         * @param allowed whether it is allowed
         * @param reason the reason
         */
        public Decision {
            if (allowed && reason != null) {
                throw new IllegalArgumentException("An allowed decision has no reason");
            }
            if (!allowed && reason == null) {
                throw new IllegalArgumentException("A refused decision needs a reason");
            }
        }

        /**
         * A positive decision.
         *
         * @return the decision
         */
        public static Decision permitted() {
            return new Decision(true, null);
        }

        /**
         * A negative decision.
         *
         * @param reason why it was refused
         * @return the decision
         */
        public static Decision refused(Reason reason) {
            return new Decision(false, reason);
        }
    }

    /** The reasons an assignment can be refused. */
    public enum Reason {
        /** The account type is missing. */
        UNKNOWN_ACCOUNT_TYPE("ASSIGNMENT_NOT_ALLOWED"),
        /** A staff account cannot be appointed as a manager. */
        ACCOUNT_TYPE_CANNOT_BE_MANAGER("ASSIGNMENT_NOT_ALLOWED"),
        /** The location type is missing. */
        UNKNOWN_LOCATION_TYPE("ASSIGNMENT_NOT_ALLOWED"),
        /** A warehouse accepts only staff assignments. */
        WAREHOUSE_ACCEPTS_STAFF_ONLY("ASSIGNMENT_NOT_ALLOWED"),
        /** The user is already the active manager of this location. */
        ALREADY_THE_ACTIVE_MANAGER("ASSIGNMENT_NOT_ALLOWED"),
        /** The location already has an active manager. */
        PRIMARY_MANAGER_EXISTS("PRIMARY_MANAGER_EXISTS");

        private final String errorCode;

        Reason(String errorCode) {
            this.errorCode = errorCode;
        }

        /**
         * The stable error code the API answers with.
         *
         * @return the error code
         */
        public String getErrorCode() {
            return errorCode;
        }
    }

    /**
     * The set of locations a manager may look after.
     *
     * <p>OOP concept: a small value object that answers the only question the callers have.
     *
     * @param locationIds the location ids
     */
    public record Scope(Set<UUID> locationIds) {

        /**
         * Creates a scope.
         *
         * @param locationIds the location ids
         */
        public Scope {
            locationIds = Set.copyOf(locationIds);
        }

        /**
         * Whether the scope contains a location.
         *
         * @param locationId the location
         * @return {@code true} when it is inside the scope
         */
        public boolean contains(UUID locationId) {
            return locationIds.contains(locationId);
        }
    }
}
