package com.merqo.pos.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.merqo.common.money.Money;

/**
 * Pure domain rules for POS sale returns and drift-free refund amounts.
 *
 * <p>Responsibility: compute remaining returnable quantity per sale line ({@code sold -
 * alreadyReturned}), reject zero/negative/over-quantity returns, and compute refund amounts so that
 * returning all units (whether at once or across multiple partial returns) refunds the exact amount
 * paid for the line without rounding drift.
 *
 * <p>OOP concept: <b>Single Responsibility &amp; Pure Function</b> — keeps return quantity and
 * remainder-safe refund math independent of repositories and HTTP controllers.
 */
public final class ReturnRules {

    private ReturnRules() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Validates a return line request and computes the exact monetary refund for that line.
     *
     * <p>For partial returns leaving at least one unit unreturned, each unit refunds {@code
     * lineTotalPaid / soldQty} rounded HALF_UP to 2 decimals. When the requested quantity returns
     * all remaining units ({@code requestedQty == soldQty - alreadyReturnedQty}), the last unit(s)
     * absorb any rounding remainder so total refunded equals {@code lineTotalPaid} exactly.
     *
     * @param soldQty original quantity sold on the line
     * @param alreadyReturnedQty quantity already returned in prior returns
     * @param requestedQty quantity requested for this return
     * @param lineTotalPaid total amount paid for the line (including tax, after discounts)
     * @param alreadyRefunded sum of amounts already refunded for this line in prior returns
     * @return exact refund amount for this return line
     */
    public static Money computeLineRefund(
            int soldQty,
            int alreadyReturnedQty,
            int requestedQty,
            Money lineTotalPaid,
            Money alreadyRefunded) {
        if (soldQty <= 0) {
            throw new IllegalArgumentException("Sold quantity must be positive.");
        }
        if (alreadyReturnedQty < 0 || alreadyReturnedQty > soldQty) {
            throw new IllegalArgumentException("Invalid alreadyReturnedQty.");
        }
        if (lineTotalPaid == null || lineTotalPaid.isNegative()) {
            throw new IllegalArgumentException("Line total paid must not be negative.");
        }
        Money priorRefund = alreadyRefunded == null ? Money.ZERO : alreadyRefunded;

        int returnable = soldQty - alreadyReturnedQty;
        if (returnable <= 0) {
            throw new PosRuleException(
                    "ALREADY_RETURNED",
                    409,
                    "All units of this sale line have already been returned.");
        }
        if (requestedQty <= 0) {
            throw new PosRuleException(
                    "VALIDATION_FAILED", 422, "Return quantity must be at least 1.");
        }
        if (requestedQty > returnable) {
            throw new PosRuleException(
                    "RETURN_QUANTITY_EXCEEDED",
                    422,
                    "Return quantity ("
                            + requestedQty
                            + ") exceeds remaining returnable quantity ("
                            + returnable
                            + ").");
        }

        Money remainingPaid = lineTotalPaid.minus(priorRefund);
        if (remainingPaid.isNegative()) {
            remainingPaid = Money.ZERO;
        }

        if (requestedQty == returnable) {
            // Returning the final remaining units takes the exact remainder to prevent rounding
            // drift.
            return remainingPaid;
        }

        Money perUnit =
                Money.of(
                        lineTotalPaid
                                .amount()
                                .divide(
                                        BigDecimal.valueOf(soldQty),
                                        Money.SCALE,
                                        RoundingMode.HALF_UP));
        Money calculated = perUnit.multipliedBy(BigDecimal.valueOf(requestedQty));
        return calculated.min(remainingPaid);
    }
}
