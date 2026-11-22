package com.merqo.access;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed catalogue of everything a user of Merqo may be allowed to do.
 *
 * <p>Responsibility: name every permission exactly once, together with the flags that decide how it
 * may be granted. A permission is a fact about the <em>system</em>, not about a user, so it lives
 * in an enum and is never stored as free text.
 *
 * <p>The four flags:
 *
 * <ul>
 *   <li><b>G (global)</b> - the permission is company wide. It does not need an assignment to a
 *       location.
 *   <li><b>L (location)</b> - the permission only applies at one location. It is effective there
 *       only when the user holds an active assignment at that location.
 *   <li><b>O (owner only)</b> - the permission is never grantable to anybody except the owner. A
 *       template or override that lists it is rejected with {@code OWNER_ONLY_PERMISSION}.
 *   <li><b>D (delegable)</b> - a manager may hand this permission to a staff member, but only if
 *       the owner put it on that manager's delegation list.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the flags live inside the enum constants, so no other class has to
 *       remember which permission is company wide.
 *   <li><b>Type safety</b> - a permission is an enum value, never a string that can be misspelled.
 *   <li><b>Open/closed principle</b> - the catalogue grows by adding constants; the resolver and
 *       the policies do not change.
 * </ul>
 */
public enum Permission {

    // ---- Users and access ---------------------------------------------------
    USER_VIEW(Module.USERS_AND_ACCESS, ScopeKind.LOCATION, false, false, "View users and accounts"),
    USER_CREATE(Module.USERS_AND_ACCESS, ScopeKind.LOCATION, false, false, "Create user accounts"),
    USER_EDIT(Module.USERS_AND_ACCESS, ScopeKind.LOCATION, false, false, "Edit user profiles"),
    USER_DEACTIVATE(
            Module.USERS_AND_ACCESS,
            ScopeKind.LOCATION,
            false,
            false,
            "Deactivate and reactivate accounts"),
    USER_ACCESS_MANAGE(
            Module.USERS_AND_ACCESS,
            ScopeKind.LOCATION,
            false,
            false,
            "Assign templates, overrides and locations"),
    MANAGER_MANAGE(
            Module.USERS_AND_ACCESS,
            ScopeKind.GLOBAL,
            true,
            false,
            "Create, edit and appoint manager accounts"),
    LOCATION_VIEW(Module.USERS_AND_ACCESS, ScopeKind.LOCATION, false, false, "View locations"),
    LOCATION_MANAGE(
            Module.USERS_AND_ACCESS,
            ScopeKind.GLOBAL,
            true,
            false,
            "Create, edit and deactivate locations"),
    TEMPLATE_MANAGE(
            Module.USERS_AND_ACCESS,
            ScopeKind.GLOBAL,
            true,
            false,
            "Create and edit permission templates"),
    SETTINGS_VIEW(
            Module.USERS_AND_ACCESS, ScopeKind.GLOBAL, false, false, "View business settings"),
    SETTINGS_MANAGE(
            Module.USERS_AND_ACCESS, ScopeKind.GLOBAL, true, false, "Change business settings"),
    AUDIT_VIEW(Module.USERS_AND_ACCESS, ScopeKind.LOCATION, false, false, "View the audit log"),

    // ---- Catalog ------------------------------------------------------------
    PRODUCT_VIEW(Module.CATALOG, ScopeKind.GLOBAL, false, true, "View products"),
    PRODUCT_CREATE(Module.CATALOG, ScopeKind.GLOBAL, false, false, "Create products"),
    PRODUCT_EDIT(Module.CATALOG, ScopeKind.GLOBAL, false, false, "Edit products"),
    PRICE_EDIT(Module.CATALOG, ScopeKind.GLOBAL, false, false, "Change selling prices"),
    BARCODE_EDIT(Module.CATALOG, ScopeKind.GLOBAL, false, false, "Change barcodes"),
    DISCOUNT_MANAGE(Module.CATALOG, ScopeKind.LOCATION, false, false, "Manage discounts"),

    // ---- Inventory ----------------------------------------------------------
    INVENTORY_VIEW(Module.INVENTORY, ScopeKind.LOCATION, false, true, "View stock"),
    INVENTORY_ADJUST(Module.INVENTORY, ScopeKind.LOCATION, false, false, "Adjust stock"),
    STOCK_COUNT(Module.INVENTORY, ScopeKind.LOCATION, false, true, "Count stock"),
    TRANSFER_VIEW(Module.INVENTORY, ScopeKind.LOCATION, false, true, "View stock transfers"),
    TRANSFER_REQUEST(Module.INVENTORY, ScopeKind.LOCATION, false, true, "Request a stock transfer"),
    TRANSFER_APPROVE(
            Module.INVENTORY, ScopeKind.LOCATION, false, false, "Approve a stock transfer"),
    TRANSFER_DISPATCH(
            Module.INVENTORY, ScopeKind.LOCATION, false, false, "Dispatch a stock transfer"),
    TRANSFER_RECEIVE(Module.INVENTORY, ScopeKind.LOCATION, false, true, "Receive a stock transfer"),

    // ---- Point of sale ------------------------------------------------------
    POS_SELL(Module.POS, ScopeKind.LOCATION, false, true, "Sell at the counter"),
    POS_DISCOUNT(Module.POS, ScopeKind.LOCATION, false, true, "Apply a discount"),
    POS_DISCOUNT_APPROVE(Module.POS, ScopeKind.LOCATION, false, false, "Approve a large discount"),
    POS_REFUND(Module.POS, ScopeKind.LOCATION, false, false, "Refund a sale"),
    POS_CASH_SESSION(Module.POS, ScopeKind.LOCATION, false, true, "Open and close a cash session"),
    POS_REPRINT(Module.POS, ScopeKind.LOCATION, false, true, "Reprint a receipt"),

    // ---- Orders -------------------------------------------------------------
    ORDER_VIEW(Module.ORDERS, ScopeKind.LOCATION, false, true, "View orders"),
    ORDER_CALL(Module.ORDERS, ScopeKind.LOCATION, false, true, "Confirm an order by phone"),
    ORDER_CONFIRM(Module.ORDERS, ScopeKind.LOCATION, false, true, "Confirm an order"),
    ORDER_DECLINE(Module.ORDERS, ScopeKind.LOCATION, false, true, "Decline an order"),
    ORDER_CANCEL(Module.ORDERS, ScopeKind.LOCATION, false, false, "Cancel an order"),
    ORDER_CREATE_MANUAL(Module.ORDERS, ScopeKind.LOCATION, false, true, "Create an order by hand"),

    // ---- Shipping -----------------------------------------------------------
    SHIPMENT_VIEW(Module.SHIPPING, ScopeKind.LOCATION, false, true, "View shipments"),
    SHIPMENT_BOOK(Module.SHIPPING, ScopeKind.LOCATION, false, true, "Book a shipment"),
    SHIPMENT_UPDATE(Module.SHIPPING, ScopeKind.LOCATION, false, false, "Update a shipment"),

    // ---- Payments -----------------------------------------------------------
    PAYMENT_VIEW(Module.PAYMENTS, ScopeKind.LOCATION, false, false, "View payments"),
    PAYMENT_RECORD(Module.PAYMENTS, ScopeKind.LOCATION, false, true, "Record a payment"),
    PAYMENT_VERIFY(Module.PAYMENTS, ScopeKind.LOCATION, false, false, "Verify a payment"),
    REFUND_APPROVE(Module.PAYMENTS, ScopeKind.LOCATION, false, false, "Approve a refund"),
    COD_SETTLEMENT_MANAGE(
            Module.PAYMENTS, ScopeKind.GLOBAL, false, false, "Manage cash on delivery settlement"),

    // ---- Suppliers ----------------------------------------------------------
    SUPPLIER_VIEW(Module.SUPPLIERS, ScopeKind.GLOBAL, false, false, "View suppliers"),
    SUPPLIER_MANAGE(Module.SUPPLIERS, ScopeKind.GLOBAL, false, false, "Manage suppliers"),
    PO_CREATE(Module.SUPPLIERS, ScopeKind.GLOBAL, false, false, "Create a purchase order"),
    PO_APPROVE(Module.SUPPLIERS, ScopeKind.GLOBAL, false, false, "Approve a purchase order"),
    PO_RECEIVE(Module.SUPPLIERS, ScopeKind.LOCATION, false, false, "Receive a purchase order"),
    SUPPLIER_PAYMENT_RECORD(
            Module.SUPPLIERS, ScopeKind.GLOBAL, false, false, "Record a supplier payment"),

    // ---- Finance ------------------------------------------------------------
    COST_VIEW(Module.FINANCE, ScopeKind.LOCATION, false, false, "View cost prices"),
    COST_EDIT_VARIABLE(
            Module.FINANCE, ScopeKind.LOCATION, false, false, "Change variable cost prices"),
    COST_EDIT_FIXED(Module.FINANCE, ScopeKind.GLOBAL, true, false, "Change fixed cost prices"),
    FINANCE_REPORT_VIEW(Module.FINANCE, ScopeKind.LOCATION, false, false, "View finance reports"),
    DASHBOARD_VIEW(Module.FINANCE, ScopeKind.LOCATION, false, false, "View the branch dashboard"),
    DASHBOARD_COMPANY_VIEW(
            Module.FINANCE, ScopeKind.GLOBAL, true, false, "View the company dashboard"),

    // ---- AI -----------------------------------------------------------------
    AI_USE(Module.AI, ScopeKind.GLOBAL, false, false, "Use the AI assistant");

    private final Module module;
    private final ScopeKind scopeKind;
    private final boolean ownerOnly;
    private final boolean delegable;
    private final String description;

    Permission(
            Module module,
            ScopeKind scopeKind,
            boolean ownerOnly,
            boolean delegable,
            String description) {
        this.module = module;
        this.scopeKind = scopeKind;
        this.ownerOnly = ownerOnly;
        this.delegable = delegable;
        this.description = description;
    }

    /**
     * The module this permission belongs to. The frontend groups the matrix by module.
     *
     * @return the module
     */
    public Module getModule() {
        return module;
    }

    /**
     * Whether the permission is company wide or per location.
     *
     * @return the scope kind
     */
    public ScopeKind getScopeKind() {
        return scopeKind;
    }

    /**
     * Whether the permission can never be granted to a non owner.
     *
     * @return {@code true} when the permission is owner only
     */
    public boolean isOwnerOnly() {
        return ownerOnly;
    }

    /**
     * Whether a manager may hand this permission to a staff member.
     *
     * @return {@code true} when the permission is delegable
     */
    public boolean isDelegable() {
        return delegable;
    }

    /**
     * A short human readable description.
     *
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Whether the permission applies at one location instead of everywhere.
     *
     * @return {@code true} for a location scoped permission
     */
    public boolean isLocationScoped() {
        return scopeKind == ScopeKind.LOCATION;
    }

    /**
     * The module groups of the permission catalogue.
     *
     * <p>OOP concept: a nested enum gives the permission catalogue a closed set of groups, so the
     * frontend matrix and the documentation always agree.
     */
    public enum Module {
        USERS_AND_ACCESS("Users and access"),
        CATALOG("Catalog"),
        INVENTORY("Inventory"),
        POS("Point of sale"),
        ORDERS("Orders"),
        SHIPPING("Shipping"),
        PAYMENTS("Payments"),
        SUPPLIERS("Suppliers"),
        FINANCE("Finance"),
        AI("AI assistant");

        private final String label;

        Module(String label) {
            this.label = label;
        }

        /**
         * The label shown in the interface and in the documentation.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * How a permission is scoped.
     *
     * <p>OOP concept: a nested enum with behaviour keeps the two kinds of scope explicit instead of
     * spreading boolean flags over the whole code base.
     */
    public enum ScopeKind {
        /** Company wide. No assignment to a location is needed. */
        GLOBAL,
        /** One location. The user needs an active assignment there. */
        LOCATION
    }

    /** The catalogue as an immutable map from code to permission, for fast lookups. */
    private static final Map<String, Permission> BY_CODE;

    static {
        Map<String, Permission> byCode = new LinkedHashMap<>();
        for (Permission permission : values()) {
            byCode.put(permission.name(), permission);
        }
        BY_CODE = Collections.unmodifiableMap(byCode);
    }

    /**
     * Looks a permission up by its stored code.
     *
     * <p>The database stores permission codes as strings. A code that this build does not know must
     * not crash the application, so this method returns an empty {@link Optional} and the caller
     * logs the problem once and carries on.
     *
     * @param code the stored code
     * @return the permission, or empty when the code is unknown
     */
    public static Optional<Permission> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code.trim().toUpperCase(java.util.Locale.ROOT)));
    }

    /**
     * Every permission of one module, in catalogue order.
     *
     * @param module the module
     * @return the permissions of that module
     */
    public static List<Permission> inModule(Module module) {
        return Arrays.stream(values())
                .filter(permission -> permission.getModule() == module)
                .toList();
    }
}
