package com.merqo.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Converts between the two time concepts Merqo needs: UTC storage and Asia/Dhaka business time.
 *
 * <p>Responsibility: offer a single, well tested place that answers "what day is it in the shop?"
 * for any moment in time.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - the business zone is a constant of the domain, not a value scattered
 *       across the code base.
 *   <li><b>Dependency injection of time</b> - the {@link Clock} is passed in, so tests can freeze
 *       time instead of waiting for midnight.
 *   <li><b>Static utility with explicit parameter</b> - every method takes the clock it should use,
 *       which keeps the class free of hidden state.
 * </ul>
 *
 * <p>Why this matters: PostgreSQL stores {@code TIMESTAMPTZ} in UTC. A report for "today" in a
 * Dhaka shop must use the Dhaka day boundary, which is six hours behind UTC. Getting this wrong
 * shifts every daily figure by six hours.
 */
public final class BusinessTime {

    /** The time zone every shop, report and receipt works in. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Dhaka");

    /** How dates are printed in the UI, for example {@code 20 Nov 2026}. */
    public static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    /** How date and time are printed in the UI. */
    public static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a");

    private BusinessTime() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * The current instant, in UTC.
     *
     * @param clock the clock to read, injected so tests can control it
     * @return the current instant
     */
    public static Instant now(Clock clock) {
        return clock.instant();
    }

    /**
     * The current moment as a UTC offset date time, ready for the database.
     *
     * @param clock the clock to read
     * @return the current moment in UTC
     */
    public static OffsetDateTime nowUtc(Clock clock) {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneId.of("UTC"));
    }

    /**
     * The current business day in Asia/Dhaka.
     *
     * @param clock the clock to read
     * @return the local business date
     */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }

    /**
     * The current business date and time in Asia/Dhaka.
     *
     * @param clock the clock to read
     * @return the local business date and time
     */
    public static LocalDateTime nowInBusinessZone(Clock clock) {
        return LocalDateTime.now(clock.withZone(BUSINESS_ZONE));
    }

    /**
     * Converts a stored UTC timestamp into the business date it belongs to.
     *
     * @param instant the stored instant
     * @return the business date in Asia/Dhaka
     */
    public static LocalDate toBusinessDate(Instant instant) {
        return instant.atZone(BUSINESS_ZONE).toLocalDate();
    }

    /**
     * Converts a stored UTC timestamp into a business zone date and time.
     *
     * @param instant the stored instant
     * @return the business date and time in Asia/Dhaka
     */
    public static LocalDateTime toBusinessDateTime(Instant instant) {
        return instant.atZone(BUSINESS_ZONE).toLocalDateTime();
    }

    /**
     * The exact instant when a business day starts in Asia/Dhaka.
     *
     * <p>A day starts at midnight in the shop's own time zone, which is six hours before UTC
     * midnight. This method is what a "today so far" report uses.
     *
     * @param businessDate the business day
     * @return the UTC instant at which that business day began
     */
    public static Instant startOfBusinessDay(LocalDate businessDate) {
        ZonedDateTime start = businessDate.atStartOfDay(BUSINESS_ZONE);
        return start.toInstant();
    }

    /**
     * The exact instant when a business day ends in Asia/Dhaka.
     *
     * @param businessDate the business day
     * @return the UTC instant at which that business day ended
     */
    public static Instant endOfBusinessDay(LocalDate businessDate) {
        ZonedDateTime end = businessDate.plusDays(1).atStartOfDay(BUSINESS_ZONE);
        return end.toInstant();
    }

    /**
     * Formats an instant for display in the business zone.
     *
     * @param instant the stored instant
     * @return a readable date and time, for example {@code 20 Nov 2026, 9:30 pm}
     */
    public static String formatForDisplay(Instant instant) {
        return instant.atZone(BUSINESS_ZONE).format(DISPLAY_DATE_TIME);
    }

    /**
     * Formats a business date for display.
     *
     * @param businessDate the business date
     * @return a readable date, for example {@code 20 Nov 2026}
     */
    public static String formatDateForDisplay(LocalDate businessDate) {
        return businessDate.format(DISPLAY_DATE);
    }
}
