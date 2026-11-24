package com.merqo.inventory.domain;

/**
 * Decides whether a variant is low on stock at a location.
 *
 * <p>Responsibility: answer "is this variant low on stock at this location?".
 *
 * <p>The rule: a variant is low when the available stock is at or below the reorder point, and the
 * reorder point is above zero. A variant without a policy row uses the default reorder point of the
 * settings, which is 5.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the class answers one question about one variant.
 *   <li><b>Null Object</b> - a missing policy is handled by passing the default, so the caller
 *       never has to check for {@code null}.
 *   <li><b>Pure function</b> - the answer depends only on the arguments.
 * </ul>
 */
public final class LowStockPolicy {

    /** The reorder point that is used when a variant has no policy row. */
    public static final int DEFAULT_REORDER_POINT = 5;

    private LowStockPolicy() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Whether a variant is low on stock.
     *
     * @param available the available stock
     * @param reorderPoint the reorder point of the variant, or {@code null} for the default
     * @return {@code true} when the variant is low on stock
     */
    public static boolean isLowStock(int available, Integer reorderPoint) {
        return isLowStock(available, reorderPoint, DEFAULT_REORDER_POINT);
    }

    /**
     * Whether a variant is low on stock, with the default reorder point of the business.
     *
     * <p>This is the version the services use. The default comes from the settings, so the owner
     * can change it without a code change. The two argument version above keeps the plain Java
     * default for the unit tests.
     *
     * @param available the available stock
     * @param reorderPoint the reorder point of the variant, or {@code null} for the default
     * @param defaultReorderPoint the default reorder point of the business
     * @return {@code true} when the variant is low on stock
     */
    public static boolean isLowStock(int available, Integer reorderPoint, int defaultReorderPoint) {
        int point = reorderPoint == null ? defaultReorderPoint : reorderPoint;
        if (point <= 0) {
            // A reorder point of zero means "do not warn about this variant".
            return false;
        }
        return available <= point;
    }
}
