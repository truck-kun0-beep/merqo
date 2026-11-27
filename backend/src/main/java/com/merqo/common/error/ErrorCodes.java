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

    /** A variant may not be active without a primary barcode. */
    public static final String BARCODE_REQUIRED = "BARCODE_REQUIRED";

    /** The barcode is already used by another variant. */
    public static final String BARCODE_IN_USE = "BARCODE_IN_USE";

    /** The barcode does not follow the rules. */
    public static final String BARCODE_INVALID = "BARCODE_INVALID";

    /** No variant carries this barcode. */
    public static final String BARCODE_NOT_FOUND = "BARCODE_NOT_FOUND";

    /** The SKU is already used by another variant. */
    public static final String SKU_IN_USE = "SKU_IN_USE";

    /** The promotion fights another promotion that is already there. */
    public static final String PROMOTION_CONFLICT = "PROMOTION_CONFLICT";

    /** There is not enough stock for what was asked. */
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

    /** The transfer may not change into that state. */
    public static final String INVALID_TRANSITION = "INVALID_TRANSITION";

    /** The variant is still in use and may not be deactivated. */
    public static final String VARIANT_IN_USE = "VARIANT_IN_USE";

    /** The category or the brand is still in use and may not be deactivated. */
    public static final String CATEGORY_IN_USE = "CATEGORY_IN_USE";

    /** The stock count is not open, so it may not be changed any more. */
    public static final String COUNT_NOT_OPEN = "COUNT_NOT_OPEN";

    /** The import file is not usable. */
    public static final String IMPORT_INVALID = "IMPORT_INVALID";

    /** No open cash session exists for this cashier at this location. */
    public static final String POS_SESSION_REQUIRED = "POS_SESSION_REQUIRED";

    /** The cashier already has an open cash session. */
    public static final String SESSION_ALREADY_OPEN = "SESSION_ALREADY_OPEN";

    /** The cash session is not open. */
    public static final String SESSION_NOT_OPEN = "SESSION_NOT_OPEN";

    /** Manual discount exceeds the cashier's limit without valid manager approval. */
    public static final String DISCOUNT_LIMIT_EXCEEDED = "DISCOUNT_LIMIT_EXCEEDED";

    /** The manager approval PIN or approval token is invalid, expired, or already used. */
    public static final String APPROVAL_INVALID = "APPROVAL_INVALID";

    /** The approval PIN does not follow the PIN policy. */
    public static final String PIN_POLICY = "PIN_POLICY";

    /** Approval PIN attempts are temporarily locked after repeated failures. */
    public static final String PIN_LOCKED = "PIN_LOCKED";

    /** A payment reference is required for the chosen payment method. */
    public static final String PAYMENT_REFERENCE_REQUIRED = "PAYMENT_REFERENCE_REQUIRED";

    /** Cash tendered is less than the bill total. */
    public static final String PAYMENT_INSUFFICIENT = "PAYMENT_INSUFFICIENT";

    /** Full card numbers may never be submitted or stored. */
    public static final String CARD_NUMBER_REJECTED = "CARD_NUMBER_REJECTED";

    /** The mobile banking transaction reference has already been used. */
    public static final String DUPLICATE_REFERENCE = "DUPLICATE_REFERENCE";

    /** The idempotency key was reused with a different request payload. */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    /** A sale must contain at least one line item. */
    public static final String EMPTY_CART = "EMPTY_CART";

    /** No returnable quantity remains on the sale or selected lines. */
    public static final String ALREADY_RETURNED = "ALREADY_RETURNED";

    /** Requested return quantity exceeds the remaining returnable quantity. */
    public static final String RETURN_QUANTITY_EXCEEDED = "RETURN_QUANTITY_EXCEEDED";

    /** The sale is outside the return window and no override permission was granted. */
    public static final String RETURN_WINDOW_EXPIRED = "RETURN_WINDOW_EXPIRED";

    /** Something unexpected happened. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
        // This class only holds constants.
    }
}
