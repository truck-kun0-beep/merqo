package com.merqo.pos.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.merqo.common.money.Money;

/**
 * Allocates a whole-bill discount proportionally across sale lines without rounding drift.
 *
 * <p>Responsibility: split a fixed whole-bill discount across lines using the largest-remainder
 * (Hamilton) method in minor units (paisa/cents) so the sum of allocated parts equals the
 * whole-bill discount to the exact cent.
 *
 * <p>Tie-break rule: when two lines have the exact same fractional remainder, the line with the
 * larger weight (line subtotal before bill discount) wins the extra cent; if weights are also
 * equal, the earlier line (lowest index) wins. This makes allocation deterministic regardless of
 * map or hash order.
 *
 * <p>OOP concept: <b>Single Responsibility &amp; Pure Function</b> — isolates proportional monetary
 * allocation as a stateless, deterministic algorithm.
 */
public final class DiscountAllocator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private DiscountAllocator() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Proportionally allocates {@code totalDiscount} over {@code lineWeights}.
     *
     * @param totalDiscount whole-bill discount to allocate (never negative)
     * @param lineWeights pre-bill-discount subtotals of each line (never negative)
     * @return list of allocated discounts matching {@code lineWeights.size()}, summing exactly to
     *     {@code totalDiscount}
     */
    public static List<Money> allocate(Money totalDiscount, List<Money> lineWeights) {
        if (totalDiscount == null || totalDiscount.isNegative()) {
            throw new IllegalArgumentException("Total discount must not be null or negative.");
        }
        if (lineWeights == null || lineWeights.isEmpty()) {
            if (!totalDiscount.isZero()) {
                throw new IllegalArgumentException(
                        "Cannot allocate a non-zero discount across zero lines.");
            }
            return List.of();
        }

        int n = lineWeights.size();
        long[] weights = new long[n];
        long totalWeight = 0;
        for (int i = 0; i < n; i++) {
            Money w = lineWeights.get(i);
            if (w == null || w.isNegative()) {
                throw new IllegalArgumentException("Line weight must not be null or negative.");
            }
            long cents = toMinorUnits(w);
            weights[i] = cents;
            totalWeight += cents;
        }

        long discountCents = toMinorUnits(totalDiscount);
        if (discountCents == 0) {
            List<Money> zeros = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                zeros.add(Money.ZERO);
            }
            return zeros;
        }
        if (discountCents > totalWeight) {
            throw new IllegalArgumentException("Discount exceeds total eligible line subtotals.");
        }

        long[] shares = new long[n];
        long[] remainders = new long[n];
        long allocatedSum = 0;

        for (int i = 0; i < n; i++) {
            long numerator = discountCents * weights[i];
            shares[i] = numerator / totalWeight;
            remainders[i] = numerator % totalWeight;
            allocatedSum += shares[i];
        }

        long leftover = discountCents - allocatedSum;
        if (leftover > 0) {
            List<Integer> order = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (weights[i] > 0) {
                    order.add(i);
                }
            }
            order.sort(
                    Comparator.comparingLong((Integer idx) -> remainders[idx])
                            .reversed()
                            .thenComparing(
                                    Comparator.comparingLong((Integer idx) -> weights[idx])
                                            .reversed())
                            .thenComparingInt(idx -> idx));
            for (int k = 0; k < leftover; k++) {
                shares[order.get(k)] += 1;
            }
        }

        List<Money> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(fromMinorUnits(shares[i]));
        }
        return result;
    }

    private static long toMinorUnits(Money money) {
        return money.amount().multiply(HUNDRED).longValueExact();
    }

    private static Money fromMinorUnits(long minorUnits) {
        return Money.of(BigDecimal.valueOf(minorUnits, 2));
    }
}
