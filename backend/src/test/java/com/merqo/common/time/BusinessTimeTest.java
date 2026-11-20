package com.merqo.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BusinessTime}, focused on the Asia/Dhaka day boundary.
 *
 * <p>These tests run without Spring. A fixed {@link Clock} is injected so the boundary is checked
 * deterministically instead of depending on when the test happens to run.
 */
class BusinessTimeTest {

    private static Clock clockAt(String isoInstant) {
        return Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("a moment just before midnight UTC is still the previous Dhaka day")
    void justBeforeUtcMidnightIsPreviousDhakaDay() {
        // 2026-11-20T17:59:59Z is 2026-11-20 23:59:59 in Dhaka (UTC+6).
        Clock clock = clockAt("2026-11-20T17:59:59Z");

        assertEquals(LocalDate.of(2026, 11, 20), BusinessTime.today(clock));
    }

    @Test
    @DisplayName("a moment after midnight UTC already belongs to the next Dhaka day")
    void justAfterUtcMidnightIsNextDhakaDay() {
        // 2026-11-20T18:00:00Z is 2026-11-21 00:00:00 in Dhaka.
        Clock clock = clockAt("2026-11-20T18:00:00Z");

        assertEquals(LocalDate.of(2026, 11, 21), BusinessTime.today(clock));
    }

    @Test
    @DisplayName("the Dhaka business day starts six hours before UTC midnight")
    void businessDayStartsAtDhakaMidnight() {
        LocalDate businessDate = LocalDate.of(2026, 11, 21);

        Instant start = BusinessTime.startOfBusinessDay(businessDate);

        // Midnight in Dhaka on 21 Nov is 18:00 UTC on 20 Nov.
        assertEquals(Instant.parse("2026-11-20T18:00:00Z"), start);
    }

    @Test
    @DisplayName("the Dhaka business day ends when the next Dhaka day starts")
    void businessDayEndsAtNextDhakaMidnight() {
        LocalDate businessDate = LocalDate.of(2026, 11, 21);

        Instant end = BusinessTime.endOfBusinessDay(businessDate);

        assertEquals(Instant.parse("2026-11-21T18:00:00Z"), end);
    }

    @Test
    @DisplayName("a business day covers exactly one day of instants")
    void businessDayIsTwentyFourHoursLong() {
        LocalDate businessDate = LocalDate.of(2026, 11, 21);

        long seconds =
                BusinessTime.endOfBusinessDay(businessDate).getEpochSecond()
                        - BusinessTime.startOfBusinessDay(businessDate).getEpochSecond();

        assertEquals(86_400L, seconds);
    }

    @Test
    @DisplayName("a stored timestamp maps to its Dhaka business date")
    void storedTimestampMapsToBusinessDate() {
        Instant stored = Instant.parse("2026-11-20T18:30:00Z");

        assertEquals(LocalDate.of(2026, 11, 21), BusinessTime.toBusinessDate(stored));
    }

    @Test
    @DisplayName("the business time is six hours ahead of UTC in Dhaka")
    void businessTimeIsSixHoursAheadOfUtc() {
        Clock clock = clockAt("2026-11-20T12:00:00Z");

        assertEquals(18, BusinessTime.nowInBusinessZone(clock).getHour());
    }

    @Test
    @DisplayName("the business zone is Asia/Dhaka")
    void businessZoneIsDhaka() {
        assertEquals("Asia/Dhaka", BusinessTime.BUSINESS_ZONE.getId());
    }

    @Test
    @DisplayName("dates print in the agreed display format")
    void printsDisplayFormat() {
        assertEquals("20 Nov 2026", BusinessTime.formatDateForDisplay(LocalDate.of(2026, 11, 20)));
    }

    @Test
    @DisplayName("the current instant is read from the injected clock")
    void readsCurrentInstantFromClock() {
        Clock clock = clockAt("2026-11-20T12:00:00Z");

        assertEquals(Instant.parse("2026-11-20T12:00:00Z"), BusinessTime.now(clock));
        assertTrue(BusinessTime.nowUtc(clock).getOffset().getTotalSeconds() == 0);
    }
}
