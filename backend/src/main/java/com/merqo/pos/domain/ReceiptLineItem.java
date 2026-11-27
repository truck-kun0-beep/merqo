package com.merqo.pos.domain;

import com.merqo.common.money.Money;

/**
 * One line item on a POS receipt.
 *
 * <p>Responsibility: hold immutable display fields for a single line item rendered by {@link
 * ReceiptRenderer}.
 *
 * <p>OOP concept: <b>Value Object</b> — immutable record matching the frontend {@code
 * ReceiptLineItem} contract.
 *
 * @param name product display name
 * @param variant variant description (e.g. {@code "Black / M"}), nullable
 * @param quantity units sold
 * @param unitPrice list unit price before discounts
 * @param discount total discount applied to the line (promotion + manual)
 * @param lineTotal final total for the line
 */
public record ReceiptLineItem(
        String name,
        String variant,
        int quantity,
        Money unitPrice,
        Money discount,
        Money lineTotal) {}
