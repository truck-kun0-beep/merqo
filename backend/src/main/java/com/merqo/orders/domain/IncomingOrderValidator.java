package com.merqo.orders.domain;

import java.math.BigDecimal;

import com.merqo.common.money.Money;

/**
 * Pure Java validator for normalized {@link IncomingOrder} payloads.
 *
 * <p>Responsibility: enforce structural, quantity, non-negative amount, maximum-line-count, total
 * arithmetic (within 0.01 tolerance), and {@link PaymentTerms} invariants prior to persisting or
 * creating orders.
 *
 * <p>OOP concept: <b>Single responsibility &amp; Fail-fast validation</b> — all payload invariants
 * execute in one stateless object without Spring or database coupling.
 */
public final class IncomingOrderValidator {

    /** Maximum number of line items accepted on a single website order. */
    public static final int MAX_LINES = 50;

    private static final Money TOTAL_TOLERANCE = Money.parse("0.01");

    private IncomingOrderValidator() {}

    /**
     * Validates an incoming normalized order and raises {@link OrderRuleException} on any rule
     * violation.
     *
     * @param order normalized incoming order
     */
    public static void validate(IncomingOrder order) {
        if (order == null) {
            throw new OrderRuleException("VALIDATION_FAILED", 422, "Order payload is required.");
        }
        if (order.externalOrderId() == null || order.externalOrderId().isBlank()) {
            throw new OrderRuleException("VALIDATION_FAILED", 422, "externalOrderId is required.");
        }
        if (order.customerName() == null || order.customerName().isBlank()) {
            throw new OrderRuleException("VALIDATION_FAILED", 422, "customerName is required.");
        }
        if (order.phone() == null || order.phone().raw().isBlank()) {
            throw new OrderRuleException("VALIDATION_FAILED", 422, "phone is required.");
        }
        if (order.address() == null || order.address().isBlank()) {
            throw new OrderRuleException("VALIDATION_FAILED", 422, "address is required.");
        }
        if (order.lines() == null || order.lines().isEmpty()) {
            throw new OrderRuleException(
                    "VALIDATION_FAILED", 422, "Order must contain at least one line.");
        }
        if (order.lines().size() > MAX_LINES) {
            throw new OrderRuleException(
                    "VALIDATION_FAILED", 422, "Order cannot exceed " + MAX_LINES + " lines.");
        }
        if (order.shippingFee() == null
                || order.discountTotal() == null
                || order.total() == null
                || order.shippingFee().isNegative()
                || order.discountTotal().isNegative()
                || order.total().isNegative()) {
            throw new OrderRuleException(
                    "VALIDATION_FAILED", 422, "Order monetary amounts must not be negative.");
        }

        Money subtotal = Money.ZERO;
        for (IncomingOrder.IncomingOrderLine line : order.lines()) {
            if (line == null || line.sku() == null || line.sku().isBlank()) {
                throw new OrderRuleException(
                        "VALIDATION_FAILED", 422, "Each order line requires a SKU.");
            }
            if (line.quantity() <= 0) {
                throw new OrderRuleException(
                        "VALIDATION_FAILED",
                        422,
                        "Line quantity must be positive for SKU " + line.sku() + ".");
            }
            if (line.unitPrice() == null || line.unitPrice().isNegative()) {
                throw new OrderRuleException(
                        "VALIDATION_FAILED",
                        422,
                        "Line unit price must be non-negative for SKU " + line.sku() + ".");
            }
            Money lineTotal = line.unitPrice().multipliedBy(BigDecimal.valueOf(line.quantity()));
            subtotal = subtotal.plus(lineTotal);
        }

        Money recomputedTotal = subtotal.minus(order.discountTotal()).plus(order.shippingFee());
        if (recomputedTotal.isNegative()) {
            throw new OrderRuleException(
                    "ORDER_TOTAL_MISMATCH", 422, "Recomputed order total cannot be negative.");
        }
        Money diff = recomputedTotal.minus(order.total()).absolute();
        if (diff.compareTo(TOTAL_TOLERANCE) > 0) {
            throw new OrderRuleException(
                    "ORDER_TOTAL_MISMATCH",
                    422,
                    "Recomputed order total ("
                            + recomputedTotal.amount().toPlainString()
                            + ") does not match stated total ("
                            + order.total().amount().toPlainString()
                            + ").");
        }

        if (order.paymentTerms() == null) {
            throw new OrderRuleException("VALIDATION_FAILED", 422, "Payment terms are required.");
        }
        order.paymentTerms().validateAgainst(order.total());
    }
}
