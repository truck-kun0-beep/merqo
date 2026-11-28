package com.merqo.payments.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.merqo.common.money.Money;
import com.merqo.common.time.BusinessTime;

/**
 * Pure domain policy identifying overdue unsettled COD balances held by couriers.
 *
 * <p>Responsibility: flag an order as having a COD gap when {@code codWithCourier > 0} and the
 * collection date is strictly older than {@code threshold.cod_gap_days} measured in {@code
 * Asia/Dhaka} calendar days using an injected {@link Clock}.
 *
 * <p>OOP concept: <b>Policy Object &amp; Explicit Clock Dependency</b> — makes Dhaka midnight
 * ({@code 18:00:00 UTC}) boundary behavior deterministic and testable without system clock
 * assumptions.
 */
public final class CodGapPolicy {

    private CodGapPolicy() {}

    /**
     * Evaluates whether an order has an overdue COD gap from a Dhaka-local collection date.
     *
     * @param codWithCourier unsettled COD balance still with courier
     * @param collectionDate date the COD was collected (Dhaka business date)
     * @param thresholdDays configured {@code threshold.cod_gap_days}
     * @param clock time source
     * @return {@code true} when unsettled balance is positive and elapsed Dhaka days exceed
     *     threshold
     */
    public static boolean hasGap(
            Money codWithCourier, LocalDate collectionDate, int thresholdDays, Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (codWithCourier == null
                || codWithCourier.compareTo(Money.ZERO) <= 0
                || collectionDate == null) {
            return false;
        }
        LocalDate todayDhaka = BusinessTime.today(clock);
        long ageDays = ChronoUnit.DAYS.between(collectionDate, todayDhaka);
        return ageDays > thresholdDays;
    }

    /**
     * Evaluates whether an order has an overdue COD gap from a stored UTC collection timestamp.
     *
     * @param codWithCourier unsettled COD balance still with courier
     * @param collectedAt UTC collection timestamp
     * @param thresholdDays configured {@code threshold.cod_gap_days}
     * @param clock time source
     * @return {@code true} when unsettled balance is positive and elapsed Dhaka days exceed
     *     threshold
     */
    public static boolean hasGap(
            Money codWithCourier, Instant collectedAt, int thresholdDays, Clock clock) {
        if (collectedAt == null) {
            return false;
        }
        return hasGap(
                codWithCourier, BusinessTime.toBusinessDate(collectedAt), thresholdDays, clock);
    }
}
