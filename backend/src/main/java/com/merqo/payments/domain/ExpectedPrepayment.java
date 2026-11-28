package com.merqo.payments.domain;

import com.merqo.common.money.Money;
import com.merqo.orders.domain.PaymentTerms.PaymentMode;

/**
 * Value object computing expected upfront prepayment and COD receivable from order payment terms.
 *
 * <p>Responsibility: derive {@code requiredPrepayment} (0 for {@code COD}, {@code advanceAmount}
 * for {@code ADVANCE_COD}, {@code total} for {@code FULL_PREPAID}) and {@code codExpected} cleanly
 * from persisted order terms.
 *
 * <p>OOP concept: <b>Value Object &amp; Static Factory</b> — immutable record preventing
 * inconsistent prepayment or COD expectation calculations across services.
 *
 * @param paymentMode order payment collection mode
 * @param total authoritative order total
 * @param requiredPrepayment upfront amount required before shipment
 * @param codExpected expected cash-on-delivery receivable
 */
public record ExpectedPrepayment(
        PaymentMode paymentMode, Money total, Money requiredPrepayment, Money codExpected) {

    /**
     * Derives expected prepayment and COD amounts from order payment terms.
     *
     * @param paymentMode order payment collection mode
     * @param total authoritative order total
     * @param advanceAmount configured advance amount on the order
     * @param codAmount configured COD amount on the order
     * @return immutable expectation value object
     */
    public static ExpectedPrepayment from(
            PaymentMode paymentMode, Money total, Money advanceAmount, Money codAmount) {
        if (paymentMode == null || total == null || advanceAmount == null || codAmount == null) {
            throw new IllegalArgumentException(
                    "PaymentMode, total, advanceAmount, and codAmount must not be null.");
        }
        Money required =
                switch (paymentMode) {
                    case COD -> Money.ZERO;
                    case ADVANCE_COD -> advanceAmount;
                    case FULL_PREPAID -> total;
                };
        Money expectedCod = paymentMode == PaymentMode.FULL_PREPAID ? Money.ZERO : codAmount;
        return new ExpectedPrepayment(paymentMode, total, required, expectedCod);
    }
}
