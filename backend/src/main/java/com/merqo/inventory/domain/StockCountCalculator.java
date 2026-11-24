package com.merqo.inventory.domain;

import java.util.List;

/**
 * The calculator of the variances of a stock count.
 *
 * <p>Responsibility: work out how much the counted quantity differs from the quantity the system
 * had when the count started, and how that difference is applied to the stock now.
 *
 * <p>The apply rule, in one sentence: <b>the difference is counted against the quantity the system
 * had at the start of the count, and it is applied to the stock of the moment.</b> So a sale that
 * happened during the count is not thrown away: the counted quantity still describes what is on the
 * shelf, and the correction is the difference between the shelf and the snapshot.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the class only does the arithmetic of a count.
 *   <li><b>Pure function</b> - no Spring, no database, so every edge case is unit tested on its
 *       own.
 *   <li><b>Immutability</b> - the answer is a record, so a caller cannot change it afterwards.
 * </ul>
 */
public final class StockCountCalculator {

    private StockCountCalculator() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * The difference of one counted line.
     *
     * @param systemQuantityAtStart the quantity the system had when the count started
     * @param countedQuantity the quantity the counter counted, may be {@code null} while the line
     *     is not counted yet
     * @return the difference, or {@code null} when the line is not counted yet
     */
    public static Integer variance(Integer systemQuantityAtStart, Integer countedQuantity) {
        if (countedQuantity == null) {
            return null;
        }
        int start = systemQuantityAtStart == null ? 0 : systemQuantityAtStart;
        return countedQuantity - start;
    }

    /**
     * The correction one line applies to the stock of the moment.
     *
     * @param systemQuantityAtStart the quantity the system had when the count started
     * @param countedQuantity the quantity the counter counted
     * @param currentOnHand the quantity the system has now
     * @return the correction, which may be negative
     */
    public static int correction(
            int systemQuantityAtStart, int countedQuantity, int currentOnHand) {
        int difference = countedQuantity - systemQuantityAtStart;
        int corrected = currentOnHand + difference;
        if (corrected < 0) {
            return 0;
        }
        return difference;
    }

    /**
     * Whether a correction may be applied to the stock of the moment.
     *
     * <p>A correction is refused when it would take the stock below the reservation, because the
     * promised stock is not the counter's to give away.
     *
     * @param systemQuantityAtStart the quantity the system had when the count started
     * @param countedQuantity the quantity the counter counted
     * @param currentOnHand the quantity the system has now
     * @param reserved the reserved stock of the moment
     * @return {@code true} when the correction may be applied
     */
    public static boolean mayApply(
            int systemQuantityAtStart, int countedQuantity, int currentOnHand, int reserved) {
        int corrected = currentOnHand + (countedQuantity - systemQuantityAtStart);
        return corrected >= reserved && corrected >= 0;
    }

    /**
     * The variances of a whole count.
     *
     * @param lines the lines of the count
     * @return the lines that carry a variance
     */
    public static List<Variance> variances(List<CountLine> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream()
                .map(
                        line ->
                                new Variance(
                                        line.variantId(),
                                        line.systemQuantityAtStart(),
                                        line.countedQuantity(),
                                        variance(
                                                line.systemQuantityAtStart(),
                                                line.countedQuantity())))
                .filter(variance -> variance.difference() != null && variance.difference() != 0)
                .toList();
    }

    /**
     * One line of a count.
     *
     * @param variantId the variant
     * @param systemQuantityAtStart the quantity the system had when the count started
     * @param countedQuantity the quantity the counter counted, may be {@code null}
     */
    public record CountLine(
            java.util.UUID variantId, int systemQuantityAtStart, Integer countedQuantity) {}

    /**
     * The variance of one counted line.
     *
     * @param variantId the variant
     * @param systemQuantityAtStart the quantity the system had when the count started
     * @param countedQuantity the quantity the counter counted, may be {@code null}
     * @param difference the difference, or {@code null} when the line is not counted yet
     */
    public record Variance(
            java.util.UUID variantId,
            int systemQuantityAtStart,
            Integer countedQuantity,
            Integer difference) {}
}
