package com.merqo.access;

/**
 * The three kinds of account Merqo knows.
 *
 * <p>Responsibility: name the kind of account and describe what the kind may do, so that the
 * service layer can ask the account type instead of checking many booleans.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - each constant carries its own behaviour ({@link
 *       #mayHoldManagerAssignments()}), so callers never write a chain of {@code if} statements.
 *   <li><b>Type safety</b> - a user is exactly one of three kinds, not a set of flags.
 * </ul>
 */
public enum AccountType {

    /** The single owner. Everything is allowed, everywhere, and nothing may change the account. */
    OWNER(true, true, true),

    /** A manager of one or more branches. May look after staff inside their own scope. */
    MANAGER(false, true, true),

    /** A staff member. Works inside the locations they are assigned to. */
    STAFF(false, false, false);

    private final boolean owner;
    private final boolean mayHoldManagerAssignments;
    private final boolean mayReceiveDelegatedPermissions;

    AccountType(
            boolean owner,
            boolean mayHoldManagerAssignments,
            boolean mayReceiveDelegatedPermissions) {
        this.owner = owner;
        this.mayHoldManagerAssignments = mayHoldManagerAssignments;
        this.mayReceiveDelegatedPermissions = mayReceiveDelegatedPermissions;
    }

    /**
     * Whether this is the owner account.
     *
     * @return {@code true} for the owner
     */
    public boolean isOwner() {
        return owner;
    }

    /**
     * Whether an account of this type may be appointed as the manager of a location.
     *
     * @return {@code true} for owner and manager
     */
    public boolean mayHoldManagerAssignments() {
        return mayHoldManagerAssignments;
    }

    /**
     * Whether an account of this type may be given permissions by a manager.
     *
     * @return {@code true} for owner and manager
     */
    public boolean mayReceiveDelegatedPermissions() {
        return mayReceiveDelegatedPermissions;
    }
}
