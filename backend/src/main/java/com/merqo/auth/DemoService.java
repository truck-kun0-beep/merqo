package com.merqo.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The list of demo accounts that the login page shows.
 *
 * <p>Responsibility: describe the demo accounts without touching the database, so the login page
 * can show them before anybody signs in.
 *
 * <p>The list only exists in demo mode. In every other mode the endpoints answer with a 404.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Immutable data</b> - the list is built once and never changes.
 *   <li><b>Single responsibility</b> - the description of the demo accounts lives in one place.
 * </ul>
 */
final class DemoService {

    /** The demo accounts and what they show. */
    static final List<DemoAccount> ACCOUNTS =
            List.of(
                    new DemoAccount(
                            "owner",
                            "Demo Owner",
                            "Owner",
                            "Every branch, the settings and the whole audit log."),
                    new DemoAccount(
                            "manager-dm",
                            "Demo Manager (two branches)",
                            "Manager of Dhanmondi and Mirpur",
                            "Runs two branches. Uttara is not visible to this account."),
                    new DemoAccount(
                            "manager-ut",
                            "Demo Manager Uttara",
                            "Manager of Uttara only",
                            "Proves that two managers do not see each other's branch."),
                    new DemoAccount(
                            "cashier",
                            "Demo Cashier",
                            "Cashier at Dhanmondi",
                            "Sells at one counter and may give a discount of up to 5 percent."),
                    new DemoAccount(
                            "orders",
                            "Demo Online Orders",
                            "Online order staff at the warehouse",
                            "Handles online orders, shipments and payments."),
                    new DemoAccount(
                            "warehouse",
                            "Demo Warehouse",
                            "Warehouse staff at the warehouse",
                            "Receives goods and moves stock inside the warehouse."));

    private DemoService() {
        // This class only holds the static description.
    }

    /**
     * The demo accounts, ready for the login page.
     *
     * @return the accounts
     */
    static List<Map<String, Object>> demoAccounts() {
        return ACCOUNTS.stream()
                .map(
                        account -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("accountKey", account.accountKey());
                            entry.put("displayName", account.displayName());
                            entry.put("role", account.role());
                            entry.put("description", account.description());
                            return entry;
                        })
                .toList();
    }

    /**
     * One demo account.
     *
     * @param accountKey the stable key used by the one click login
     * @param displayName the name shown on the button
     * @param role what the account may do
     * @param description a short explanation
     */
    record DemoAccount(String accountKey, String displayName, String role, String description) {}
}
