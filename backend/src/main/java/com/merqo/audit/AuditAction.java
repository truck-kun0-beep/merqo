package com.merqo.audit;

/**
 * Every action that Merqo writes into the audit log.
 *
 * <p>Responsibility: name the audited actions in one place, so the audit screen can filter on them
 * and so a missing action is easy to spot.
 *
 * <p>OOP concept: a closed enum instead of free text. A new action is added here and nowhere else.
 */
public enum AuditAction {

    /** The first owner account was created from the environment. */
    OWNER_BOOTSTRAPPED,

    /** The owner password was reset from the environment. */
    OWNER_PASSWORD_RESET_BY_ENV,

    /** A login succeeded. */
    LOGIN_SUCCEEDED,

    /** A login was refused because the account was locked. */
    LOGIN_LOCKED,

    /** A login failed. Only written for accounts that exist. */
    LOGIN_FAILED,

    /** A user changed their own password. */
    PASSWORD_CHANGED,

    /** The owner or a manager reset the password of somebody else. */
    PASSWORD_RESET,

    /** A user account was created. */
    USER_CREATED,

    /** A user account was edited. */
    USER_UPDATED,

    /** A user account was deactivated. */
    USER_DEACTIVATED,

    /** A user account was activated again. */
    USER_ACTIVATED,

    /** The templates of a user were replaced. */
    TEMPLATES_ASSIGNED,

    /** The overrides of a user were replaced. */
    OVERRIDES_CHANGED,

    /** The delegation list of a manager was changed. */
    DELEGATIONS_CHANGED,

    /** A user was assigned to a location. */
    ASSIGNMENT_CREATED,

    /** An assignment was ended. */
    ASSIGNMENT_ENDED,

    /** The manager of a branch was replaced by somebody else. */
    MANAGER_REPLACED,

    /** A location was created. */
    LOCATION_CREATED,

    /** A location was edited. */
    LOCATION_UPDATED,

    /** A location was deactivated. */
    LOCATION_DEACTIVATED,

    /** A location was activated again. */
    LOCATION_ACTIVATED,

    /** The online fulfilment location was changed. */
    ONLINE_LOCATION_CHANGED,

    /** A permission template was created. */
    TEMPLATE_CREATED,

    /** A permission template was edited. */
    TEMPLATE_UPDATED,

    /** A business setting was changed. */
    SETTING_CHANGED
}
