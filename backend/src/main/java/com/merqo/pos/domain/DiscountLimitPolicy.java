package com.merqo.pos.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.merqo.catalog.domain.DiscountType;
import com.merqo.common.money.Money;

/**
 * Policy governing manual POS discounts and approval ceilings.
 *
 * <p>Responsibility: validate manual discount intent (line or whole-bill), reject fixed discounts
 * at or above the eligible price after promotion, enforce {@code POS_DISCOUNT} permission, and
 * decide whether the requested discount is within the cashier's {@code maxDiscountPercent} or
 * requires manager approval ({@code POS_DISCOUNT_APPROVE}).
 *
 * <p>OOP concept: <b>Policy / Strategy Pattern</b> — separates authorization and limit thresholds
 * from pricing arithmetic and controller plumbing.
 */
public final class DiscountLimitPolicy {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private DiscountLimitPolicy() {
        // Utility class: not meant to be instantiated.
    }

    /** Outcome of evaluating a manual discount against user limits. */
    public enum Decision {
        /** No manual discount was requested. */
        NO_DISCOUNT,
        /** Manual discount is allowed without an approval token. */
        WITHIN_LIMIT,
        /** Manual discount exceeds the cashier's personal ceiling and needs manager approval. */
        NEEDS_APPROVAL
    }

    /**
     * Resolved discount amount, effective percentage, and approval decision.
     *
     * @param decision whether approval is needed
     * @param effectivePercent effective discount percentage against the base price after promotion
     * @param discountAmount resolved monetary discount amount
     */
    public record Evaluation(
            Decision decision, BigDecimal effectivePercent, Money discountAmount) {}

    /**
     * Resolves a manual discount amount against a base price after promotion and checks the
     * cashier's personal limit.
     *
     * @param discountType PERCENT or FIXED, or {@code null} for no discount
     * @param discountValue percentage (0..100] or fixed amount, or {@code null} for no discount
     * @param basePriceAfterPromotion eligible price after promotion
     * @param hasDiscountPermission whether caller holds {@code POS_DISCOUNT}
     * @param hasApprovePermission whether caller holds {@code POS_DISCOUNT_APPROVE} at the location
     * @param cashierMaxDiscountPercent cashier's personal limit (0..100)
     * @return the evaluation result
     */
    public static Evaluation evaluate(
            DiscountType discountType,
            BigDecimal discountValue,
            Money basePriceAfterPromotion,
            boolean hasDiscountPermission,
            boolean hasApprovePermission,
            BigDecimal cashierMaxDiscountPercent) {

        if (basePriceAfterPromotion == null || basePriceAfterPromotion.isNegative()) {
            throw new PosRuleException(
                    "VALIDATION_FAILED", 422, "Base price after promotion must not be negative.");
        }

        if (discountType == null && discountValue == null) {
            return new Evaluation(Decision.NO_DISCOUNT, BigDecimal.ZERO, Money.ZERO);
        }
        if (discountType == null || discountValue == null) {
            throw new PosRuleException(
                    "VALIDATION_FAILED",
                    422,
                    "Both discount type and discount value must be provided together.");
        }
        if (discountValue.signum() < 0) {
            throw new PosRuleException(
                    "VALIDATION_FAILED", 422, "Manual discount value must not be negative.");
        }
        if (discountValue.signum() == 0) {
            return new Evaluation(Decision.NO_DISCOUNT, BigDecimal.ZERO, Money.ZERO);
        }

        if (!hasDiscountPermission && !hasApprovePermission) {
            throw new PosRuleException(
                    "FORBIDDEN",
                    403,
                    "You do not have the permission POS_DISCOUNT at this location.");
        }

        if (basePriceAfterPromotion.isZero()) {
            throw new PosRuleException(
                    "VALIDATION_FAILED",
                    422,
                    "Cannot apply a manual discount to a zero-priced item.");
        }

        Money resolvedAmount;
        BigDecimal effectivePercent;

        if (discountType == DiscountType.PERCENT) {
            if (discountValue.compareTo(ONE_HUNDRED) > 0) {
                throw new PosRuleException(
                        "VALIDATION_FAILED", 422, "Discount percentage cannot exceed 100.");
            }
            resolvedAmount = basePriceAfterPromotion.percentageOf(discountValue);
            effectivePercent = discountValue.setScale(2, RoundingMode.HALF_UP);
        } else {
            resolvedAmount = Money.of(discountValue);
            if (resolvedAmount.compareTo(basePriceAfterPromotion) >= 0) {
                throw new PosRuleException(
                        "VALIDATION_FAILED",
                        422,
                        "Fixed manual discount must be less than the price after promotion.");
            }
            effectivePercent =
                    resolvedAmount
                            .amount()
                            .multiply(ONE_HUNDRED)
                            .divide(basePriceAfterPromotion.amount(), 4, RoundingMode.HALF_UP)
                            .setScale(2, RoundingMode.HALF_UP);
        }

        if (hasApprovePermission) {
            return new Evaluation(Decision.WITHIN_LIMIT, effectivePercent, resolvedAmount);
        }

        BigDecimal limit =
                cashierMaxDiscountPercent == null ? BigDecimal.ZERO : cashierMaxDiscountPercent;
        Decision decision =
                effectivePercent.compareTo(limit) <= 0
                        ? Decision.WITHIN_LIMIT
                        : Decision.NEEDS_APPROVAL;
        return new Evaluation(decision, effectivePercent, resolvedAmount);
    }
}
