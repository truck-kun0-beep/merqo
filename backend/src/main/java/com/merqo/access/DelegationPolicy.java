package com.merqo.access;

import java.util.Set;
import java.util.UUID;

/**
 * Decides whether a manager may hand a permission to a staff member at a location.
 *
 * <p>Responsibility: answer one question with one rule set, so that the "grant permission" service
 * does not have to know the four conditions.
 *
 * <p>A manager may grant permission P at location L only when <b>all</b> of these hold:
 *
 * <ol>
 *   <li>P is flagged delegable.
 *   <li>P is on the manager's own delegation list, which only the owner can change.
 *   <li>The manager holds P at L themselves.
 *   <li>L is inside the manager's own scope.
 * </ol>
 *
 * <p>A manager can never grant anything to themselves, to another manager or to the owner, and can
 * never touch an account outside their scope.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the class answers "may this manager delegate this?".
 *   <li><b>Encapsulation</b> - the conditions are hidden behind {@link #check}; the caller gets a
 *       reason code, not the internals.
 *   <li><b>Open/closed principle</b> - a new condition is added inside this class only.
 *   <li><b>Value object</b> - {@link Decision} is a small immutable answer.
 * </ul>
 */
public class DelegationPolicy {

    /**
     * Checks whether a manager may delegate a permission.
     *
     * @param request the facts of the request
     * @return the decision, always with a reason
     */
    public Decision check(Request request) {
        Permission permission = request.permission();

        if (permission.isOwnerOnly()) {
            return Decision.refused(Reason.OWNER_ONLY_PERMISSION);
        }
        if (!permission.isDelegable()) {
            return Decision.refused(Reason.NOT_DELEGABLE);
        }
        if (!request.delegationList().contains(permission)) {
            return Decision.refused(Reason.NOT_IN_DELEGATION_LIST);
        }
        // The scope is checked before the permission itself. A manager can only hold a permission
        // at the locations inside their own scope, so a location outside that scope is refused
        // with the clearer reason.
        if (!request.managerScope().contains(request.locationId())) {
            return Decision.refused(Reason.LOCATION_OUTSIDE_MANAGER_SCOPE);
        }
        if (!request.managerPermissions().can(permission, request.locationId())) {
            return Decision.refused(Reason.MANAGER_DOES_NOT_HOLD_PERMISSION);
        }
        if (request.targetAccountType() == AccountType.OWNER) {
            return Decision.refused(Reason.TARGET_IS_OWNER);
        }
        if (request.targetAccountType() == AccountType.MANAGER) {
            return Decision.refused(Reason.TARGET_IS_MANAGER);
        }
        if (request.targetUserId().equals(request.managerUserId())) {
            return Decision.refused(Reason.TARGET_IS_THE_MANAGER);
        }
        return Decision.permitted();
    }

    /**
     * The facts of one delegation request.
     *
     * @param managerUserId the manager who wants to delegate
     * @param targetUserId the staff member who would receive the permission
     * @param targetAccountType the account type of the staff member
     * @param permission the permission to delegate
     * @param locationId the location it would apply at
     * @param managerPermissions the effective permissions of the manager
     * @param managerScope the location ids the manager is assigned to
     * @param delegationList the permissions the owner allows this manager to delegate
     */
    public record Request(
            UUID managerUserId,
            UUID targetUserId,
            AccountType targetAccountType,
            Permission permission,
            UUID locationId,
            EffectivePermissions managerPermissions,
            Set<UUID> managerScope,
            Set<Permission> delegationList) {

        /**
         * Canonical constructor with defensive copies.
         *
         * @param managerUserId the manager
         * @param targetUserId the target
         * @param targetAccountType the target account type
         * @param permission the permission
         * @param locationId the location
         * @param managerPermissions the manager permissions
         * @param managerScope the manager scope
         * @param delegationList the delegation list
         */
        public Request {
            managerScope = Set.copyOf(managerScope);
            delegationList = Set.copyOf(delegationList);
        }
    }

    /**
     * The answer of the policy.
     *
     * @param allowed whether the delegation is allowed
     * @param reason why it was refused, {@code null} when it is allowed
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

    /**
     * The reasons a delegation can be refused.
     *
     * <p>Each reason maps to one stable error code, so the interface can show a clear message.
     */
    public enum Reason {
        /** The permission is owner only and can never be delegated. */
        OWNER_ONLY_PERMISSION("OWNER_ONLY_PERMISSION"),
        /** The permission is not flagged delegable. */
        NOT_DELEGABLE("DELEGATION_NOT_ALLOWED"),
        /** The permission is not on the manager's delegation list. */
        NOT_IN_DELEGATION_LIST("DELEGATION_NOT_ALLOWED"),
        /** The manager does not hold the permission themselves. */
        MANAGER_DOES_NOT_HOLD_PERMISSION("DELEGATION_NOT_ALLOWED"),
        /** The location is outside the manager's scope. */
        LOCATION_OUTSIDE_MANAGER_SCOPE("DELEGATION_NOT_ALLOWED"),
        /** The target is the owner. */
        TARGET_IS_OWNER("DELEGATION_NOT_ALLOWED"),
        /** The target is another manager. */
        TARGET_IS_MANAGER("DELEGATION_NOT_ALLOWED"),
        /** The manager tried to delegate to themselves. */
        TARGET_IS_THE_MANAGER("DELEGATION_NOT_ALLOWED");

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
}
