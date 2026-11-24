package com.merqo.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Picks the one promotion that applies to a variant.
 *
 * <p>Responsibility: answer "which promotion applies to this variant, in this channel, at this
 * location, right now?".
 *
 * <p>There is only ever one answer, because the conflict detector refuses a second promotion that
 * would cover the same variant, the same channel, the same location and the same period. The
 * resolver therefore only has to pick the one that is active.
 *
 * <p>When more than one promotion is active anyway (for example because the data was written before
 * the conflict rule existed), the resolver picks the one that gives the bigger discount, so the
 * customer always gets the better price and the answer is still deterministic.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the resolver only chooses. It never writes and never checks
 *       permissions.
 *   <li><b>Pure function</b> - the answer depends only on the arguments.
 *   <li><b>Strategy</b> - the way the discount is compared is a small strategy, so the rule "the
 *       bigger discount wins" is stated in one place.
 * </ul>
 */
public final class PromotionResolver {

    private PromotionResolver() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Picks the promotion that applies.
     *
     * @param promotions the promotions to choose from
     * @param scope the channel the customer buys in
     * @param locationId the location of the purchase
     * @param moment the moment of the purchase
     * @return the promotion that applies, or {@code null} when none does
     */
    public static Promotion resolve(
            List<Promotion> promotions, Scope scope, UUID locationId, Instant moment) {

        Promotion best = null;
        for (Promotion promotion : promotions) {
            if (!promotion.isActiveAt(moment)) {
                continue;
            }
            if (!promotion.getScope().covers(scope)) {
                continue;
            }
            if (best == null || isBetter(promotion, best)) {
                best = promotion;
            }
        }
        return best;
    }

    /**
     * Whether one promotion gives a better price than another.
     *
     * <p>A percentage promotion is compared with the other percentage promotion, and a fixed
     * promotion with the other fixed promotion. A percentage is normally better, because it shrinks
     * with the price, so a price of 1000 with ten percent is better than a fixed hundred.
     *
     * @param candidate the promotion that might be better
     * @param current the promotion that is chosen at the moment
     * @return {@code true} when the candidate gives the bigger discount
     */
    static boolean isBetter(Promotion candidate, Promotion current) {
        if (candidate.getDiscountType() != current.getDiscountType()) {
            return candidate.getDiscountType() == DiscountType.PERCENT;
        }
        return candidate.getValue().compareTo(current.getValue()) > 0;
    }
}
