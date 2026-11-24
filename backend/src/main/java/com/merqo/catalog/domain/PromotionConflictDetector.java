package com.merqo.catalog.domain;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Finds the promotions that fight each other.
 *
 * <p>Responsibility: answer "do these two promotions conflict?" for every pair of promotions.
 *
 * <p>Two promotions conflict when <b>all four</b> of these hold:
 *
 * <ol>
 *   <li>They cover at least one variant in common.
 *   <li>Their scopes overlap, so they cover a channel in common.
 *   <li>Their date ranges overlap. An open range counts as overlapping everything.
 *   <li>Their location sets intersect. A promotion that covers every location intersects
 *       everything.
 * </ol>
 *
 * <p>The detector is a separate class on purpose. The rule is a rule about the data, and it can be
 * unit tested on its own with no Spring and no database. The service that writes promotions asks it
 * and turns the answer into an error code.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the class answers one question: are these two in conflict?
 *   <li><b>Pure function</b> - the answer depends only on the arguments, so every combination is
 *       unit tested.
 *   <li><b>Composition</b> - the answer is a {@link Conflict} record that carries both promotions
 *       and the variants they share, instead of a bare boolean.
 * </ul>
 */
public final class PromotionConflictDetector {

    private PromotionConflictDetector() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Whether two promotions conflict.
     *
     * @param first the first promotion
     * @param second the second promotion
     * @param firstVariants the variants of the first promotion
     * @param secondVariants the variants of the second promotion
     * @param firstLocations the locations of the first promotion
     * @param secondLocations the locations of the second promotion
     * @return the conflict, or {@code null} when they do not conflict
     */
    public static Conflict detect(
            Promotion first,
            Promotion second,
            Set<UUID> firstVariants,
            Set<UUID> secondVariants,
            Set<UUID> firstLocations,
            Set<UUID> secondLocations) {

        if (first == null || second == null) {
            return null;
        }
        if (first.getId().equals(second.getId())) {
            return null;
        }
        if (!first.getScope().overlaps(second.getScope())) {
            return null;
        }
        if (!first.datesOverlap(second)) {
            return null;
        }
        Set<UUID> sharedVariants = intersect(firstVariants, secondVariants);
        if (sharedVariants.isEmpty()) {
            return null;
        }
        if (!locationsIntersect(first, second, firstLocations, secondLocations)) {
            return null;
        }
        return new Conflict(first, second, sharedVariants);
    }

    /**
     * Finds every conflict of one promotion with a list of other promotions.
     *
     * @param promotion the promotion to test
     * @param variants the variants of that promotion
     * @param locations the locations of that promotion
     * @param others the other promotions, with their variants and locations
     * @return the conflicts, in the order of the other promotions
     */
    public static List<Conflict> detectAll(
            Promotion promotion,
            Set<UUID> variants,
            Set<UUID> locations,
            List<OtherPromotion> others) {
        List<Conflict> conflicts = new java.util.ArrayList<>();
        for (OtherPromotion other : others) {
            Conflict conflict =
                    detect(
                            promotion,
                            other.promotion(),
                            variants,
                            other.variants(),
                            locations,
                            other.locations());
            if (conflict != null) {
                conflicts.add(conflict);
            }
        }
        return conflicts;
    }

    private static boolean locationsIntersect(
            Promotion first,
            Promotion second,
            Set<UUID> firstLocations,
            Set<UUID> secondLocations) {
        if (first.isAllLocations() || second.isAllLocations()) {
            // A promotion that covers every location intersects every other one.
            return true;
        }
        return !intersect(firstLocations, secondLocations).isEmpty();
    }

    private static Set<UUID> intersect(Set<UUID> first, Set<UUID> second) {
        Set<UUID> shared = new LinkedHashSet<>();
        if (first == null || second == null) {
            return shared;
        }
        for (UUID id : first) {
            if (second.contains(id)) {
                shared.add(id);
            }
        }
        return shared;
    }

    /**
     * One conflict between two promotions.
     *
     * @param first the first promotion
     * @param second the second promotion
     * @param sharedVariants the variants the two promotions have in common
     */
    public record Conflict(Promotion first, Promotion second, Set<UUID> sharedVariants) {

        /**
         * Canonical constructor. The set of variants is copied.
         *
         * @param first the first promotion
         * @param second the second promotion
         * @param sharedVariants the shared variants
         */
        public Conflict {
            sharedVariants = Set.copyOf(sharedVariants);
        }
    }

    /**
     * One other promotion, with the variants and the locations it covers.
     *
     * @param promotion the promotion
     * @param variants the variants it covers
     * @param locations the locations it covers
     */
    public record OtherPromotion(Promotion promotion, Set<UUID> variants, Set<UUID> locations) {

        /**
         * Canonical constructor. The collections are copied.
         *
         * @param promotion the promotion
         * @param variants the variants it covers
         * @param locations the locations it covers
         */
        public OtherPromotion {
            variants = Set.copyOf(variants);
            locations = Set.copyOf(locations);
        }

        /**
         * Builds one other promotion.
         *
         * @param promotion the promotion
         * @param variants the variants it covers
         * @param locations the locations it covers
         * @return the other promotion
         */
        public static OtherPromotion of(
                Promotion promotion, Collection<UUID> variants, Collection<UUID> locations) {
            return new OtherPromotion(promotion, Set.copyOf(variants), Set.copyOf(locations));
        }
    }
}
