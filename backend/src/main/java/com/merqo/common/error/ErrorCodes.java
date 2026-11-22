package com.merqo.common.error;

/**
 * The stable error codes of the Merqo API.
 *
 * <p>Responsibility: name every error code in one place, so that the frontend, the tests and the
 * documentation all agree on the same strings.
 *
 * <p>OOP concept: a class with only constants. It is the single source of truth for the contract
 * between the backend and the frontend.
 */
public final class ErrorCodes {

    /** The caller is not signed in, or the session is no longer valid. */
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    /**
     * The identifier and the password did not match. The message never says which one was wrong.
     */
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

    /** The account is locked because of too many failed attempts. */
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";

    /** Too many requests from this address. */
    public static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";

    /** The caller may not do this. */
    public static final String FORBIDDEN = "FORBIDDEN";

    /** The CSRF token was missing or wrong, so the request was refused. */
    public static final String CSRF_TOKEN_INVALID = "CSRF_TOKEN_INVALID";

    /** The record does not exist, or it is outside the caller's scope. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** The request was not valid. */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** The request contradicts the current state. */
    public static final String CONFLICT = "CONFLICT";

    /** The password does not follow the password policy. */
    public static final String PASSWORD_POLICY = "PASSWORD_POLICY";

    /** The owner account may not be changed by anybody else. */
    public static final String OWNER_PROTECTED = "OWNER_PROTECTED";

    /** The permission is owner only and can never be granted to somebody else. */
    public static final String OWNER_ONLY_PERMISSION = "OWNER_ONLY_PERMISSION";

    /** The location already has an active manager. */
    public static final String PRIMARY_MANAGER_EXISTS = "PRIMARY_MANAGER_EXISTS";

    /** The manager may not delegate this permission. */
    public static final String DELEGATION_NOT_ALLOWED = "DELEGATION_NOT_ALLOWED";

    /** The assignment is not allowed. */
    public static final String ASSIGNMENT_NOT_ALLOWED = "ASSIGNMENT_NOT_ALLOWED";

    /** The location is still in use and may not be deactivated. */
    public static final String LOCATION_IN_USE = "LOCATION_IN_USE";

    /** The user must change the password before doing anything else. */
    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

    /** A value already exists. */
    public static final String DUPLICATE_VALUE = "DUPLICATE_VALUE";

    /** The request was malformed. */
    public static final String BAD_REQUEST = "BAD_REQUEST";

    /** Something unexpected happened. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
        // This class only holds constants.
    }
}
