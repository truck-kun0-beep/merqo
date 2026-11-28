package com.merqo.payments.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.merqo.common.money.Money;

/**
 * Pure domain calculator for courier COD settlement batches.
 *
 * <p>Responsibility: compute per-line {@code net = codCollectedAmount - fee}, enforce per-order
 * {@code codWithCourier} ceilings, sum fees and expected net, derive settlement difference ({@code
 * NONE}, {@code OVER}, or {@code SHORT}), and require an explanatory note whenever non-zero
 * variance exists ({@code SETTLEMENT_MISMATCH}).
 *
 * <p>OOP concept: <b>Stateless Domain Calculator &amp; Immutable Value Objects</b> — isolates all
 * reconciliation math in {@link Money} arithmetic cleanly outside transactions.
 */
public final class SettlementCalculator {

    /** Channels by which courier settlement funds arrive. */
    public enum ReceiveMethod {
        BANK,
        BKASH,
        NAGAD,
        CASH;

        public static ReceiveMethod fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED", 422, "Receive method is required.");
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED", 422, "Unsupported receive method: " + raw);
            }
        }
    }

    /** Classification of settlement cash variance. */
    public enum DifferenceKind {
        NONE,
        OVER,
        SHORT
    }

    /**
     * Input line representing one order included in a settlement batch.
     *
     * @param orderId order identifier
     * @param orderNumber order number for readable errors
     * @param codCollectedAmount gross COD amount settled for this order
     * @param fee courier fee deducted for this order
     * @param orderCodWithCourier remaining unsettled COD currently held by courier for this order
     */
    public record SettlementLineInput(
            UUID orderId,
            String orderNumber,
            Money codCollectedAmount,
            Money fee,
            Money orderCodWithCourier) {}

    /**
     * Computed settlement line ready for persistence.
     *
     * @param orderId order identifier
     * @param orderNumber human order number
     * @param codCollectedAmount gross collected settled
     * @param fee deducted fee
     * @param net {@code codCollectedAmount - fee}
     */
    public record ComputedSettlementLine(
            UUID orderId, String orderNumber, Money codCollectedAmount, Money fee, Money net) {}

    /**
     * Full settlement reconciliation result satisfying {@code sumNet + difference ==
     * amountReceived}.
     *
     * @param lines computed lines
     * @param totalCollected sum of gross collected amounts
     * @param feesTotal sum of fees deducted
     * @param sumNet sum of line net amounts
     * @param amountReceived actual amount received from courier
     * @param difference {@code amountReceived - sumNet}
     * @param differenceKind {@code NONE}, {@code OVER}, or {@code SHORT}
     */
    public record SettlementCalculation(
            List<ComputedSettlementLine> lines,
            Money totalCollected,
            Money feesTotal,
            Money sumNet,
            Money amountReceived,
            Money difference,
            DifferenceKind differenceKind) {}

    private SettlementCalculator() {}

    /**
     * Validates lines and computes totals and settlement difference.
     *
     * @param inputs settlement lines
     * @param amountReceived total amount received
     * @param note optional remarks (required if difference is non-zero)
     * @return immutable settlement calculation
     */
    public static SettlementCalculation calculate(
            List<SettlementLineInput> inputs, Money amountReceived, String note) {
        if (inputs == null || inputs.isEmpty()) {
            throw new PaymentRuleException(
                    "VALIDATION_FAILED", 422, "Settlement must include at least one order line.");
        }
        if (amountReceived == null || amountReceived.isNegative()) {
            throw new PaymentRuleException(
                    "VALIDATION_FAILED", 422, "Amount received must not be negative.");
        }
        CardNumberGuard.rejectCardNumberInText(note);

        Set<UUID> seenOrders = new HashSet<>();
        List<ComputedSettlementLine> computed = new ArrayList<>(inputs.size());
        Money totalCollected = Money.ZERO;
        Money feesTotal = Money.ZERO;
        Money sumNet = Money.ZERO;

        for (SettlementLineInput input : inputs) {
            if (input.orderId() == null) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED", 422, "Each settlement line requires an order.");
            }
            if (!seenOrders.add(input.orderId())) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED",
                        422,
                        "Order "
                                + (input.orderNumber() != null
                                        ? input.orderNumber()
                                        : input.orderId())
                                + " appears more than once in this settlement.");
            }
            Money collected = input.codCollectedAmount();
            Money fee = input.fee() == null ? Money.ZERO : input.fee();
            Money withCourier =
                    input.orderCodWithCourier() == null ? Money.ZERO : input.orderCodWithCourier();

            if (collected == null || collected.compareTo(Money.ZERO) <= 0) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED",
                        422,
                        "Line collected amount must be greater than zero.");
            }
            if (fee.isNegative() || fee.compareTo(collected) > 0) {
                throw new PaymentRuleException(
                        "VALIDATION_FAILED",
                        422,
                        "Line fee must be between 0 and collected amount.");
            }
            if (collected.compareTo(withCourier) > 0) {
                String label =
                        input.orderNumber() != null
                                ? input.orderNumber()
                                : input.orderId().toString();
                throw new PaymentRuleException(
                        "COD_EXCEEDS_EXPECTED",
                        409,
                        "Settlement amount for order "
                                + label
                                + " exceeds unsettled COD with courier ("
                                + withCourier.amount().toPlainString()
                                + ").");
            }
            Money net = collected.minus(fee);
            computed.add(
                    new ComputedSettlementLine(
                            input.orderId(), input.orderNumber(), collected, fee, net));
            totalCollected = totalCollected.plus(collected);
            feesTotal = feesTotal.plus(fee);
            sumNet = sumNet.plus(net);
        }

        Money difference = amountReceived.minus(sumNet);
        DifferenceKind kind;
        if (difference.isZero()) {
            kind = DifferenceKind.NONE;
        } else if (difference.isNegative()) {
            kind = DifferenceKind.SHORT;
        } else {
            kind = DifferenceKind.OVER;
        }

        if (kind != DifferenceKind.NONE && (note == null || note.isBlank())) {
            throw new PaymentRuleException(
                    "SETTLEMENT_MISMATCH",
                    422,
                    "A note is required when amount received does not match expected net ("
                            + kind.name()
                            + " "
                            + difference.amount().toPlainString()
                            + ").");
        }

        return new SettlementCalculation(
                List.copyOf(computed),
                totalCollected,
                feesTotal,
                sumNet,
                amountReceived,
                difference,
                kind);
    }
}
