package com.merqo.pos.domain;

import java.time.Instant;
import java.util.List;

import com.merqo.common.money.Money;

/**
 * Complete receipt payload passed to a {@link ReceiptRenderer}.
 *
 * <p>Responsibility: capture all header, line-item, payment, footer, and duplicate-print details
 * for a completed sale receipt.
 *
 * <p>OOP concept: <b>Value Object &amp; Composition</b> — immutable snapshot shared across JSON API
 * and plain-text thermal printer renderers.
 *
 * @param businessName business display name from settings
 * @param branchName branch location name
 * @param branchAddress branch address (nullable)
 * @param branchPhone branch phone (nullable)
 * @param receiptNumber formatted receipt number ({@code MQ-YYYY-NNNNNN})
 * @param issuedAt UTC sale timestamp
 * @param cashierName cashier display name
 * @param lines receipt line items
 * @param subtotal net subtotal before tax
 * @param discount combined promotion + manual discount total
 * @param tax total tax
 * @param total final amount payable
 * @param paymentMethod payment method used
 * @param paymentReference normalized payment reference (nullable)
 * @param footerText receipt footer from settings (nullable)
 * @param isDuplicate {@code true} on reprints, {@code false} on original print
 */
public record ReceiptData(
        String businessName,
        String branchName,
        String branchAddress,
        String branchPhone,
        String receiptNumber,
        Instant issuedAt,
        String cashierName,
        List<ReceiptLineItem> lines,
        Money subtotal,
        Money discount,
        Money tax,
        Money total,
        PaymentMethod paymentMethod,
        String paymentReference,
        String footerText,
        boolean isDuplicate) {}
