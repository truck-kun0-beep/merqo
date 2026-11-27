package com.merqo.pos.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.merqo.common.money.Money;

/**
 * Pure calculator for end-of-shift cash session reconciliation.
 *
 * <p>Responsibility: compute server-side expected totals per payment method (opening cash + cash
 * sales - cash refunds for cash; sales - refunds for non-cash methods) and compute per-method
 * variances ({@code counted - expected}) when closing a session.
 *
 * <p>OOP concept: <b>Single Responsibility &amp; Value Object</b> — isolates shift reconciliation
 * arithmetic into immutable records testable without database state.
 */
public final class CashSessionReconciler {

    private CashSessionReconciler() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * One payment-method amount from a sale or refund belonging to a session.
     *
     * @param method payment or refund method
     * @param amount non-negative amount
     */
    public record MethodAmount(PaymentMethod method, Money amount) {}

    /**
     * Expected totals per payment method for a cash session.
     *
     * @param expectedByMethod expected amount keyed by {@link PaymentMethod}
     */
    public record ExpectedTotals(Map<PaymentMethod, Money> expectedByMethod) {
        /**
         * Returns the expected amount for a specific method.
         *
         * @param method the payment method
         * @return expected amount (never {@code null})
         */
        public Money get(PaymentMethod method) {
            return expectedByMethod.getOrDefault(method, Money.ZERO);
        }
    }

    /**
     * Per-method reconciliation line showing expected, counted, and difference.
     *
     * @param method payment method
     * @param expected server-computed expected amount
     * @param counted staff-counted amount
     * @param difference {@code counted - expected} (positive = overage, negative = shortage)
     */
    public record MethodVariance(
            PaymentMethod method, Money expected, Money counted, Money difference) {}

    /**
     * Full reconciliation summary for closing a session.
     *
     * @param byMethod breakdown for all four {@link PaymentMethod} values
     * @param anyDifferenceNonZero {@code true} if any method has a non-zero difference
     */
    public record ReconciliationResult(
            Map<PaymentMethod, MethodVariance> byMethod, boolean anyDifferenceNonZero) {}

    /**
     * Computes server-side expected totals across all four payment methods.
     *
     * @param openingCash opening cash float in the drawer
     * @param sales completed sales in the session
     * @param refunds completed refunds linked to the session
     * @return expected totals per method
     */
    public static ExpectedTotals expected(
            Money openingCash, List<MethodAmount> sales, List<MethodAmount> refunds) {
        if (openingCash == null || openingCash.isNegative()) {
            throw new PosRuleException(
                    "VALIDATION_FAILED", 422, "Opening cash must not be negative.");
        }

        EnumMap<PaymentMethod, Money> totals = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod method : PaymentMethod.values()) {
            totals.put(method, method.affectsCashDrawer() ? openingCash : Money.ZERO);
        }

        if (sales != null) {
            for (MethodAmount sale : sales) {
                if (sale.amount() != null) {
                    totals.put(sale.method(), totals.get(sale.method()).plus(sale.amount()));
                }
            }
        }
        if (refunds != null) {
            for (MethodAmount refund : refunds) {
                if (refund.amount() != null) {
                    totals.put(refund.method(), totals.get(refund.method()).minus(refund.amount()));
                }
            }
        }

        return new ExpectedTotals(Map.copyOf(totals));
    }

    /**
     * Reconciles counted amounts against server-computed expected totals.
     *
     * <p>Any missing non-cash counted amount defaults to {@link Money#ZERO} (or exact expected if
     * callers pass explicit values), while {@code countedCash} is required.
     *
     * @param expected server-computed expected totals
     * @param countedByMethod counted amounts per method (missing methods default to {@link
     *     Money#ZERO})
     * @return reconciliation result with per-method differences and note requirement flag
     */
    public static ReconciliationResult reconcile(
            ExpectedTotals expected, Map<PaymentMethod, Money> countedByMethod) {
        if (expected == null) {
            throw new IllegalArgumentException("Expected totals must not be null.");
        }
        EnumMap<PaymentMethod, MethodVariance> breakdown = new EnumMap<>(PaymentMethod.class);
        boolean anyDiff = false;

        for (PaymentMethod method : PaymentMethod.values()) {
            Money exp = expected.get(method);
            Money counted =
                    countedByMethod != null && countedByMethod.get(method) != null
                            ? countedByMethod.get(method)
                            : Money.ZERO;
            if (counted.isNegative()) {
                throw new PosRuleException(
                        "VALIDATION_FAILED",
                        422,
                        "Counted " + method.name() + " amount must not be negative.");
            }
            Money diff = counted.minus(exp);
            if (!diff.isZero()) {
                anyDiff = true;
            }
            breakdown.put(method, new MethodVariance(method, exp, counted, diff));
        }

        return new ReconciliationResult(Map.copyOf(breakdown), anyDiff);
    }
}
