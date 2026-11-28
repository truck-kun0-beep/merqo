package com.merqo.payments.domain;

import com.merqo.common.money.Money;

/**
 * Derived financial payment, refund, and COD position of an online or manual order.
 *
 * <p>Responsibility: compute verified, pending, rejected, refunded, net-held, refundable,
 * prepayment satisfaction ({@code readyForShipment}), and courier COD balances from append-only
 * source totals without ever persisting derived columns.
 *
 * <p>OOP concept: <b>Immutable Value Object &amp; Pure Computation</b> — guarantees every caller
 * (order detail, refund checks, COD ledgers, Phase 6 shipping gate) reads identical figures.
 */
public record OrderPaymentPosition(
        Money requiredPrepayment,
        Money verifiedPaid,
        Money pendingPaid,
        Money rejectedPaid,
        Money refundedPaid,
        Money refundReserved,
        Money netHeld,
        Money refundable,
        boolean prepaymentSatisfied,
        Money shortfall,
        Money codExpected,
        Money codCollected,
        Money codSettledGross,
        Money codFeesDeducted,
        Money codWithCourier,
        Money codUncollected,
        boolean readyForShipment) {

    /**
     * Computes an order's payment and COD position from immutable ledger sums.
     *
     * @param expected expected prepayment and COD figures
     * @param verifiedPaid sum of {@code VERIFIED} payment amounts
     * @param pendingPaid sum of {@code PENDING_VERIFICATION} payment amounts
     * @param rejectedPaid sum of {@code REJECTED} payment amounts
     * @param refundedPaid sum of {@code PAID} refund requests
     * @param refundReserved sum of {@code REQUESTED} and {@code APPROVED} refund requests
     * @param codCollected sum of recorded COD collections
     * @param codSettledGross sum of gross collected amounts allocated in courier settlements
     * @param codFeesDeducted sum of courier settlement fees deducted
     * @return computed immutable position
     */
    public static OrderPaymentPosition compute(
            ExpectedPrepayment expected,
            Money verifiedPaid,
            Money pendingPaid,
            Money rejectedPaid,
            Money refundedPaid,
            Money refundReserved,
            Money codCollected,
            Money codSettledGross,
            Money codFeesDeducted) {
        Money required = nonNullZero(expected.requiredPrepayment());
        Money verified = nonNullZero(verifiedPaid);
        Money pending = nonNullZero(pendingPaid);
        Money rejected = nonNullZero(rejectedPaid);
        Money refunded = nonNullZero(refundedPaid);
        Money reserved = nonNullZero(refundReserved);
        Money collected = nonNullZero(codCollected);
        Money settledGross = nonNullZero(codSettledGross);
        Money fees = nonNullZero(codFeesDeducted);
        Money expectedCod = nonNullZero(expected.codExpected());

        Money netHeld = verified.minus(refunded);
        Money refundable = netHeld.minus(reserved).max(Money.ZERO);
        boolean satisfied = verified.compareTo(required) >= 0;
        Money shortfall = satisfied ? Money.ZERO : required.minus(verified);
        Money withCourier = collected.minus(settledGross);
        Money uncollected =
                expectedCod.compareTo(collected) > 0 ? expectedCod.minus(collected) : Money.ZERO;

        return new OrderPaymentPosition(
                required,
                verified,
                pending,
                rejected,
                refunded,
                reserved,
                netHeld,
                refundable,
                satisfied,
                shortfall,
                expectedCod,
                collected,
                settledGross,
                fees,
                withCourier,
                uncollected,
                satisfied);
    }

    private static Money nonNullZero(Money value) {
        return value == null ? Money.ZERO : value;
    }
}
