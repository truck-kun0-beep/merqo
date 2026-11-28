package com.merqo.orders.domain;

/**
 * Pure Java calculator for online sellable warehouse stock availability.
 *
 * <p>Responsibility: compute {@code max(0, onHand - reserved - softHolds - onlineSafetyStock)} so
 * website availability queries and staff stock diagnostics share the exact same formula.
 */
public final class OnlineAvailabilityCalculator {

    private OnlineAvailabilityCalculator() {}

    /**
     * Computes online sellable quantity for a SKU at the fulfillment warehouse.
     *
     * @param onHand physical warehouse units
     * @param reserved hard-reserved units on confirmed orders
     * @param softHolds derived quantity in {@code PENDING_CONFIRMATION} and {@code FOLLOW_UP}
     *     orders
     * @param onlineSafetyStock safety buffer from {@code inventory_policies}
     * @return non-negative available online units
     */
    public static int available(int onHand, int reserved, int softHolds, int onlineSafetyStock) {
        int effectiveSoftHolds = Math.max(0, softHolds);
        int effectiveSafety = Math.max(0, onlineSafetyStock);
        long result = (long) onHand - reserved - effectiveSoftHolds - effectiveSafety;
        if (result <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, result);
    }
}
