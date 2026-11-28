package com.merqo.orders.domain;

/**
 * Pure Java policy classifying customer phone history into actionable risk badges.
 *
 * <p>Responsibility: evaluate derived {@link PhoneHistory} against the configurable {@code
 * threshold.refusal_risk_count} setting (default 2). {@code HIGH} always beats {@code WATCH}.
 */
public final class PhoneRiskPolicy {

    /** Default refusal/return threshold when setting is missing or non-positive. */
    public static final int DEFAULT_REFUSAL_THRESHOLD = 2;

    /** Threshold for cancelled + declined orders triggering WATCH. */
    public static final int WATCH_THRESHOLD = 2;

    private PhoneRiskPolicy() {}

    /** Risk badge levels surfaced in order and customer screens. */
    public enum RiskTier {
        NEW,
        OK,
        WATCH,
        HIGH
    }

    /**
     * Classifies a phone's order history into a {@link RiskTier}.
     *
     * @param history derived counts for the normalized phone
     * @param refusalRiskThreshold configured {@code threshold.refusal_risk_count}
     * @return risk tier
     */
    public static RiskTier evaluate(PhoneHistory history, int refusalRiskThreshold) {
        if (history == null || history.total() <= 0) {
            return RiskTier.NEW;
        }
        int effectiveThreshold =
                refusalRiskThreshold > 0 ? refusalRiskThreshold : DEFAULT_REFUSAL_THRESHOLD;
        if (history.refused() + history.returned() >= effectiveThreshold) {
            return RiskTier.HIGH;
        }
        if (history.cancelled() + history.declined() >= WATCH_THRESHOLD) {
            return RiskTier.WATCH;
        }
        return RiskTier.OK;
    }
}
