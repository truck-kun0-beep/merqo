package com.merqo.access;

/**
 * The kind of link between a user and a location.
 *
 * <p>Responsibility: distinguish "this person runs this branch" from "this person works here".
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the rule "a warehouse accepts only staff" lives in {@link
 *       #isAllowedAt(LocationType)} instead of being repeated in every service.
 *   <li><b>Single responsibility</b> - the enum knows the rules of an assignment; the services know
 *       the workflow.
 * </ul>
 */
public enum AssignmentType {

    /** The manager of a location. At most one active manager per branch. */
    MANAGER,

    /** A staff member working at a location. */
    STAFF;

    /**
     * Whether this assignment type is allowed at a location of the given type.
     *
     * <p>A warehouse never has a manager, because a warehouse is not a shop floor with a till.
     *
     * @param locationType the type of the location
     * @return {@code true} when the combination is allowed
     */
    public boolean isAllowedAt(LocationType locationType) {
        if (locationType == LocationType.WAREHOUSE) {
            return this == STAFF;
        }
        return true;
    }
}
