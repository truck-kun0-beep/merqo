package com.merqo.inventory.domain;

import java.math.BigDecimal;

/**
 * Decides whether a stock adjustment is unusual.
 *
 * <p>Responsibility: answer "is this adjustment worth a look?".
 *
 * <p>The rule: an adjustment is unusual when the value of the change at cost is above the threshold
 * {@code threshold.unusual_adjustment_value}. The threshold is a business value, so it comes from
 * the settings and never from a constant in the code.
 *
 * <p>There is no approval workflow in this version. The flag is written and the Action Center of
 * Phase 8 reads it later. That decision is written down in ASSUMPTIONS.md.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the class answers one question about one adjustment.
 *   <li><b>Pure function</b> - the answer depends only on the arguments, so it is unit tested
 *       without Spring.
 *   <li><b>Strategy</b> - the threshold is a parameter, so the same rule works for a different
 *       threshold without a new class.
 * </ul>
 */
public final class AdjustmentFlagPolicy {

    private AdjustmentFlagPolicy() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Whether an adjustment is unusual.
     *
     * @param valueAtCost the value of the change at cost
     * @param threshold the threshold above which an adjustment is unusual
     * @return {@code true} when the adjustment is unusual
     */
    public static boolean isUnusual(BigDecimal valueAtCost, BigDecimal threshold) {
        if (valueAtCost == null || threshold == null) {
            return false;
        }
        return valueAtCost.abs().compareTo(threshold) > 0;
    }
}
