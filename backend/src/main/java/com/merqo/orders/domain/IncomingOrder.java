package com.merqo.orders.domain;

import java.util.List;

import com.merqo.common.money.Money;

/**
 * Normalized external order produced by any {@link WebsiteOrderSource} before persistence.
 *
 * <p>Responsibility: represent an incoming order in one canonical, source-independent Java
 * structure so the intake service never depends on any specific ecommerce JSON dialect.
 *
 * @param externalOrderId caller-supplied idempotency key from the website
 * @param customerName recipient full name
 * @param phone parsed Bangladeshi phone number (may hold {@code normalized == null})
 * @param address delivery street/area text
 * @param district optional city/district name
 * @param lines normalized line items
 * @param shippingFee delivery charge
 * @param discountTotal order-level discount
 * @param total stated grand total
 * @param paymentTerms payment breakdown and unverified advance claim
 * @param note optional customer/website note
 */
public record IncomingOrder(
        String externalOrderId,
        String customerName,
        PhoneNumber phone,
        String address,
        String district,
        List<IncomingOrderLine> lines,
        Money shippingFee,
        Money discountTotal,
        Money total,
        PaymentTerms paymentTerms,
        String note) {

    /**
     * Single normalized line item from a website order.
     *
     * @param sku product variant SKU
     * @param productName optional display name sent by the website
     * @param quantity ordered pieces
     * @param unitPrice price per unit charged on the website
     */
    public record IncomingOrderLine(
            String sku, String productName, int quantity, Money unitPrice) {}
}
