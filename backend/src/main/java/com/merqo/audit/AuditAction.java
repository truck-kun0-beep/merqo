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
    SETTING_CHANGED,

    /** A category was created. */
    CATEGORY_CREATED,

    /** A category was edited. */
    CATEGORY_UPDATED,

    /** A category was deactivated. */
    CATEGORY_DEACTIVATED,

    /** A category was activated again. */
    CATEGORY_ACTIVATED,

    /** A brand was created. */
    BRAND_CREATED,

    /** A brand was edited. */
    BRAND_UPDATED,

    /** A brand was deactivated. */
    BRAND_DEACTIVATED,

    /** A brand was activated again. */
    BRAND_ACTIVATED,

    /** A product was created. */
    PRODUCT_CREATED,

    /** A product was edited. */
    PRODUCT_UPDATED,

    /** A product was deactivated. */
    PRODUCT_DEACTIVATED,

    /** A product was activated again. */
    PRODUCT_ACTIVATED,

    /** A variant was created. */
    VARIANT_CREATED,

    /** A variant was edited. */
    VARIANT_UPDATED,

    /** A variant was deactivated. */
    VARIANT_DEACTIVATED,

    /** A variant was activated again. */
    VARIANT_ACTIVATED,

    /** The selling price of a variant was changed. */
    PRICE_CHANGED,

    /** The cost price of a variant was changed. */
    COST_CHANGED,

    /** A barcode was added to a variant. */
    BARCODE_ADDED,

    /** A barcode of a variant was changed. */
    BARCODE_CHANGED,

    /** A barcode of a variant was removed. */
    BARCODE_REMOVED,

    /** A promotion was created. */
    PROMOTION_CREATED,

    /** A promotion was edited. */
    PROMOTION_UPDATED,

    /** A promotion was ended. */
    PROMOTION_ENDED,

    /** A promotion was enabled. */
    PROMOTION_ENABLED,

    /** A promotion was disabled. */
    PROMOTION_DISABLED,

    /** A stock adjustment was created. */
    ADJUSTMENT_CREATED,

    /** A stock policy was changed. */
    INVENTORY_POLICY_CHANGED,

    /** A stock count was started. */
    COUNT_STARTED,

    /** A stock count was submitted. */
    COUNT_SUBMITTED,

    /** A stock count was applied. */
    COUNT_APPLIED,

    /** A stock count was cancelled. */
    COUNT_CANCELLED,

    /** A stock transfer was requested. */
    TRANSFER_REQUESTED,

    /** A stock transfer was approved. */
    TRANSFER_APPROVED,

    /** A stock transfer was rejected. */
    TRANSFER_REJECTED,

    /** A stock transfer was dispatched. */
    TRANSFER_DISPATCHED,

    /** A stock transfer was received. */
    TRANSFER_RECEIVED,

    /** A stock transfer was closed with a shortage. */
    TRANSFER_CLOSED_WITH_SHORTAGE,

    /** A stock transfer was cancelled. */
    TRANSFER_CANCELLED,

    /** A catalog import was committed. */
    CATALOG_IMPORTED,

    /** A POS sale was completed. */
    POS_SALE_COMPLETED,

    /** A manager approved an above-limit POS discount. */
    POS_DISCOUNT_APPROVED,

    /** A manager approval PIN attempt failed. */
    POS_APPROVAL_FAILED,

    /** A POS return was created. */
    POS_RETURN_CREATED,

    /** A receipt was reprinted as a duplicate. */
    RECEIPT_REPRINTED,

    /** A cash session was opened. */
    CASH_SESSION_OPENED,

    /** A cash session was closed. */
    CASH_SESSION_CLOSED,

    /** A user set or changed their approval PIN. */
    APPROVAL_PIN_CHANGED,

    /** Goods in inspection were resolved by restocking or writing off. */
    INSPECTION_RESOLVED,

    /** An external website integration client was created. */
    INTEGRATION_CLIENT_CREATED,

    /** An external website integration client's secret was rotated. */
    INTEGRATION_CLIENT_ROTATED,

    /** An external website integration client was disabled. */
    INTEGRATION_CLIENT_DISABLED,

    /** An external website integration client was re-enabled. */
    INTEGRATION_CLIENT_ENABLED,

    /** A website order was accepted via the integration intake pipeline. */
    ORDER_RECEIVED,

    /** Staff created a manual order. */
    MANUAL_ORDER_CREATED,

    /** Staff updated customer contact/address fields on an order. */
    ORDER_CUSTOMER_EDITED,

    /** Staff confirmed an order and reserved warehouse stock. */
    ORDER_CONFIRMED,

    /** Staff declined an unconfirmed order. */
    ORDER_DECLINED,

    /** Staff cancelled an order (releasing reservation if previously confirmed). */
    ORDER_CANCELLED,

    /** Staff recorded a customer cancellation request on an order. */
    ORDER_CANCELLATION_REQUESTED,

    /** An order payment record was created. */
    PAYMENT_RECORDED,

    /** An order payment was verified by staff or a provider check. */
    PAYMENT_VERIFIED,

    /** An order payment was rejected, releasing any TrxID reference. */
    PAYMENT_REJECTED,

    /** A refund request was created for an order. */
    REFUND_REQUESTED,

    /** A refund request was approved. */
    REFUND_APPROVED,

    /** A refund request was rejected. */
    REFUND_REJECTED,

    /** A refund payout was completed. */
    REFUND_PAID,

    /** A COD collection was recorded for a confirmed order. */
    COD_COLLECTION_RECORDED,

    /** A courier COD settlement batch was recorded. */
    COD_SETTLEMENT_RECORDED
}
