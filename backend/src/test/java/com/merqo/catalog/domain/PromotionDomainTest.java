package com.merqo.catalog.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The tests of the promotion rules.
 *
 * <p>These tests run without Spring and without a database.
 */
class PromotionDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final UUID VARIANT = UUID.randomUUID();
    private static final UUID OTHER_VARIANT = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID OTHER_LOCATION = UUID.randomUUID();

    private static Promotion promotion(
            DiscountType type,
            java.math.BigDecimal value,
            Scope scope,
            boolean allLocations,
            Instant startsAt,
            Instant endsAt,
            boolean enabled) {
        return new Promotion(
                UUID.randomUUID(),
                "Test promotion",
                type,
                value,
                scope,
                allLocations,
                startsAt,
                endsAt,
                enabled);
    }

    @Nested
    @DisplayName("the life of a promotion")
    class Life {

        @Test
        @DisplayName("is active between the start and the end")
        void isActiveBetweenTheDates() {
            Promotion promotion =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            NOW.minusSeconds(3600),
                            NOW.plusSeconds(3600),
                            true);

            assertTrue(promotion.isActiveAt(NOW));
        }

        @Test
        @DisplayName("is not active before it starts")
        void isNotActiveBeforeItStarts() {
            Promotion promotion =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            NOW.plusSeconds(60),
                            NOW.plusSeconds(3600),
                            true);

            assertFalse(promotion.isActiveAt(NOW));
        }

        @Test
        @DisplayName("is not active after it ends")
        void isNotActiveAfterItEnds() {
            Promotion promotion =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            NOW.minusSeconds(3600),
                            NOW.minusSeconds(60),
                            true);

            assertFalse(promotion.isActiveAt(NOW));
        }

        @Test
        @DisplayName("is not active when it is switched off")
        void isNotActiveWhenItIsSwitchedOff() {
            Promotion promotion =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            null,
                            null,
                            false);

            assertFalse(promotion.isActiveAt(NOW));
        }

        @Test
        @DisplayName("is active with an open date range")
        void isActiveWithAnOpenDateRange() {
            Promotion promotion =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            null,
                            null,
                            true);

            assertTrue(promotion.isActiveAt(NOW));
        }
    }

    @Nested
    @DisplayName("the conflict detector")
    class Conflicts {

        @Test
        @DisplayName("finds a conflict when all four conditions hold")
        void findsAConflict() {
            Promotion first =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            false,
                            NOW.minusSeconds(3600),
                            NOW.plusSeconds(3600),
                            true);
            Promotion second =
                    promotion(
                            DiscountType.FIXED,
                            new java.math.BigDecimal("50"),
                            Scope.POS,
                            false,
                            NOW.minusSeconds(60),
                            NOW.plusSeconds(7200),
                            true);

            PromotionConflictDetector.Conflict conflict =
                    PromotionConflictDetector.detect(
                            first,
                            second,
                            Set.of(VARIANT),
                            Set.of(VARIANT),
                            Set.of(LOCATION),
                            Set.of(LOCATION));

            assertNotNull(conflict);
            assertEquals(Set.of(VARIANT), conflict.sharedVariants());
        }

        @Test
        @DisplayName("finds no conflict when the scopes do not overlap")
        void findsNoConflictWhenTheScopesDoNotOverlap() {
            Promotion first =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            null,
                            null,
                            true);
            Promotion second =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.ONLINE,
                            true,
                            null,
                            null,
                            true);

            assertNull(
                    PromotionConflictDetector.detect(
                            first,
                            second,
                            Set.of(VARIANT),
                            Set.of(VARIANT),
                            Set.of(LOCATION),
                            Set.of(LOCATION)));
        }

        @Test
        @DisplayName("finds no conflict when the dates do not overlap")
        void findsNoConflictWhenTheDatesDoNotOverlap() {
            Promotion first =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            NOW.minusSeconds(7200),
                            NOW.minusSeconds(3600),
                            true);
            Promotion second =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            NOW.minusSeconds(60),
                            NOW.plusSeconds(3600),
                            true);

            assertNull(
                    PromotionConflictDetector.detect(
                            first,
                            second,
                            Set.of(VARIANT),
                            Set.of(VARIANT),
                            Set.of(LOCATION),
                            Set.of(LOCATION)));
        }

        @Test
        @DisplayName("finds no conflict when the locations do not meet")
        void findsNoConflictWhenTheLocationsDoNotMeet() {
            Promotion first =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            false,
                            null,
                            null,
                            true);
            Promotion second =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            false,
                            null,
                            null,
                            true);

            assertNull(
                    PromotionConflictDetector.detect(
                            first,
                            second,
                            Set.of(VARIANT),
                            Set.of(VARIANT),
                            Set.of(LOCATION),
                            Set.of(OTHER_LOCATION)));
        }

        @Test
        @DisplayName("finds no conflict when the variants do not meet")
        void findsNoConflictWhenTheVariantsDoNotMeet() {
            Promotion first =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            null,
                            null,
                            true);
            Promotion second =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            null,
                            null,
                            true);

            assertNull(
                    PromotionConflictDetector.detect(
                            first,
                            second,
                            Set.of(VARIANT),
                            Set.of(OTHER_VARIANT),
                            Set.of(LOCATION),
                            Set.of(LOCATION)));
        }

        @Test
        @DisplayName("finds a conflict when one promotion covers every location")
        void findsAConflictWithAllLocations() {
            Promotion first =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            false,
                            null,
                            null,
                            true);
            Promotion second =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            null,
                            null,
                            true);

            assertNotNull(
                    PromotionConflictDetector.detect(
                            first,
                            second,
                            Set.of(VARIANT),
                            Set.of(VARIANT),
                            Set.of(LOCATION),
                            Set.of(OTHER_LOCATION)));
        }
    }

    @Nested
    @DisplayName("the resolver")
    class Resolver {

        @Test
        @DisplayName("picks the promotion that is active")
        void picksTheActivePromotion() {
            Promotion active =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.POS,
                            true,
                            null,
                            null,
                            true);
            Promotion inactive =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("30"),
                            Scope.POS,
                            true,
                            NOW.plusSeconds(3600),
                            null,
                            true);

            Promotion chosen =
                    PromotionResolver.resolve(List.of(active, inactive), Scope.POS, LOCATION, NOW);

            assertEquals(active, chosen);
        }

        @Test
        @DisplayName("picks nothing when no promotion covers the channel")
        void picksNothingWhenTheChannelDoesNotMatch() {
            Promotion online =
                    promotion(
                            DiscountType.PERCENT,
                            new java.math.BigDecimal("10"),
                            Scope.ONLINE,
                            true,
                            null,
                            null,
                            true);

            assertNull(PromotionResolver.resolve(List.of(online), Scope.POS, LOCATION, NOW));
        }
    }
}
