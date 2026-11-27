package com.merqo.pos.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.LinePricing;
import com.merqo.catalog.domain.PriceCalculator;
import com.merqo.catalog.domain.TaxPolicy;
import com.merqo.common.money.Money;

/**
 * Pure Java calculator for an entire POS sale basket.
 *
 * <p>Responsibility: take server-resolved list prices, POS promotions, manual discount intent, and
 * tax settings for all cart lines, compose {@link PriceCalculator} and {@link DiscountAllocator},
 * and return an immutable {@link SaleTotals} breakdown.
 *
 * <p>OOP concept: <b>Composition &amp; Pure Function</b> — delegates per-unit pricing and tax rules
 * to {@link PriceCalculator} and {@link TaxPolicy}, delegates whole-bill splitting to {@link
 * DiscountAllocator}, and holds no mutable state.
 */
public final class SaleCalculator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private SaleCalculator() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Input line resolved by the server from the catalog and active POS promotions.
     *
     * @param variantId variant id
     * @param listPrice server-side selling price per unit
     * @param promotionDiscount per-unit promotion discount (nullable)
     * @param quantity whole quantity (&gt;= 1)
     * @param manualDiscountType line-level manual discount type (nullable)
     * @param manualDiscountValue line-level manual discount percent or fixed line amount (nullable)
     * @param taxRate effective tax rate percent (nullable)
     * @param taxInclusive whether list price is tax-inclusive
     * @param costPrice latest unit cost (nullable)
     */
    public record SaleLineInput(
            UUID variantId,
            Money listPrice,
            Money promotionDiscount,
            int quantity,
            DiscountType manualDiscountType,
            BigDecimal manualDiscountValue,
            BigDecimal taxRate,
            boolean taxInclusive,
            Money costPrice) {}

    /**
     * Computes all line-level and bill-level totals for a POS sale.
     *
     * @param inputs resolved sale lines (must be non-empty)
     * @param billDiscountType optional whole-bill discount type
     * @param billDiscountValue optional whole-bill discount value
     * @return immutable {@link SaleTotals}
     */
    public static SaleTotals calculate(
            List<SaleLineInput> inputs,
            DiscountType billDiscountType,
            BigDecimal billDiscountValue) {
        if (inputs == null || inputs.isEmpty()) {
            throw new PosRuleException("EMPTY_CART", 422, "Sale must contain at least one item.");
        }

        List<LinePricing> basePricings = new ArrayList<>(inputs.size());
        List<Money> lineManualDiscounts = new ArrayList<>(inputs.size());
        List<Money> preBillSubtotals = new ArrayList<>(inputs.size());
        List<BigDecimal> lineManualPercents = new ArrayList<>(inputs.size());

        Money totalPromotionDiscount = Money.ZERO;
        Money sumAfterPromoBeforeBill = Money.ZERO;
        Money sumPreBillSubtotals = Money.ZERO;
        BigDecimal maxEffectiveManualPercent = BigDecimal.ZERO;

        for (SaleLineInput input : inputs) {
            if (input.quantity() < 1) {
                throw new PosRuleException(
                        "VALIDATION_FAILED", 422, "Quantity must be at least 1.");
            }
            LinePricing promoPricing =
                    PriceCalculator.calculate(
                            input.listPrice(),
                            input.promotionDiscount(),
                            null,
                            input.quantity(),
                            BigDecimal.ZERO,
                            false,
                            input.costPrice());

            Money afterPromoLineSubtotal = promoPricing.lineSubtotal();
            DiscountLimitPolicy.Evaluation lineDiscountEval =
                    DiscountLimitPolicy.evaluate(
                            input.manualDiscountType(),
                            input.manualDiscountValue(),
                            afterPromoLineSubtotal,
                            true,
                            true,
                            ONE_HUNDRED);

            Money lineManual = lineDiscountEval.discountAmount();
            Money preBillSubtotal = afterPromoLineSubtotal.minus(lineManual);

            basePricings.add(promoPricing);
            lineManualDiscounts.add(lineManual);
            preBillSubtotals.add(preBillSubtotal);
            lineManualPercents.add(lineDiscountEval.effectivePercent());

            if (lineDiscountEval.effectivePercent().compareTo(maxEffectiveManualPercent) > 0) {
                maxEffectiveManualPercent = lineDiscountEval.effectivePercent();
            }

            Money promoLineTotal =
                    promoPricing
                            .promotionDiscount()
                            .multipliedBy(BigDecimal.valueOf(input.quantity()));
            totalPromotionDiscount = totalPromotionDiscount.plus(promoLineTotal);
            sumAfterPromoBeforeBill = sumAfterPromoBeforeBill.plus(afterPromoLineSubtotal);
            sumPreBillSubtotals = sumPreBillSubtotals.plus(preBillSubtotal);
        }

        Money billDiscount = Money.ZERO;
        if (billDiscountType != null || billDiscountValue != null) {
            DiscountLimitPolicy.Evaluation billEval =
                    DiscountLimitPolicy.evaluate(
                            billDiscountType,
                            billDiscountValue,
                            sumPreBillSubtotals,
                            true,
                            true,
                            ONE_HUNDRED);
            billDiscount = billEval.discountAmount();
        }

        List<Money> allocatedBillDiscounts =
                DiscountAllocator.allocate(billDiscount, preBillSubtotals);

        List<SaleTotals.ComputedLine> computedLines = new ArrayList<>(inputs.size());
        Money totalManualDiscount = Money.ZERO;
        Money totalSubtotal = Money.ZERO;
        Money totalTax = Money.ZERO;
        Money grandTotal = Money.ZERO;
        boolean anyBelowCost = false;

        for (int i = 0; i < inputs.size(); i++) {
            SaleLineInput input = inputs.get(i);
            LinePricing base = basePricings.get(i);
            Money lineManual = lineManualDiscounts.get(i);
            Money billAlloc = allocatedBillDiscounts.get(i);
            Money combinedManual = lineManual.plus(billAlloc);
            Money lineSubtotal = preBillSubtotals.get(i).minus(billAlloc);

            Money netUnitPrice =
                    Money.of(
                            lineSubtotal
                                    .amount()
                                    .divide(
                                            BigDecimal.valueOf(input.quantity()),
                                            2,
                                            RoundingMode.HALF_UP));

            BigDecimal rate = input.taxRate() == null ? BigDecimal.ZERO : input.taxRate();
            Money lineTax;
            Money lineTotal;
            if (input.taxInclusive()) {
                BigDecimal divisor = rate.add(ONE_HUNDRED);
                lineTax =
                        rate.signum() == 0
                                ? Money.ZERO
                                : Money.of(
                                        lineSubtotal
                                                .amount()
                                                .multiply(rate)
                                                .divide(
                                                        divisor,
                                                        Money.SCALE,
                                                        RoundingMode.HALF_UP));
                lineTotal = lineSubtotal;
            } else {
                lineTax =
                        Money.of(
                                TaxPolicy.taxOn(lineSubtotal.amount(), rate)
                                        .setScale(2, RoundingMode.HALF_UP));
                lineTotal = lineSubtotal.plus(lineTax);
            }

            boolean belowCost =
                    input.costPrice() != null && netUnitPrice.compareTo(input.costPrice()) < 0;
            if (belowCost) {
                anyBelowCost = true;
            }

            BigDecimal lineAfterPromoAmount = base.lineSubtotal().amount();
            BigDecimal lineEffectivePercent = BigDecimal.ZERO;
            if (lineAfterPromoAmount.signum() > 0 && !combinedManual.isZero()) {
                lineEffectivePercent =
                        combinedManual
                                .amount()
                                .multiply(ONE_HUNDRED)
                                .divide(lineAfterPromoAmount, 4, RoundingMode.HALF_UP)
                                .setScale(2, RoundingMode.HALF_UP);
                if (lineEffectivePercent.compareTo(maxEffectiveManualPercent) > 0) {
                    maxEffectiveManualPercent = lineEffectivePercent;
                }
            }

            computedLines.add(
                    new SaleTotals.ComputedLine(
                            input.variantId(),
                            base,
                            lineManual,
                            billAlloc,
                            combinedManual,
                            netUnitPrice,
                            lineSubtotal,
                            lineTax,
                            lineTotal,
                            rate,
                            input.taxInclusive(),
                            belowCost,
                            lineEffectivePercent));

            totalManualDiscount = totalManualDiscount.plus(combinedManual);
            totalSubtotal = totalSubtotal.plus(lineSubtotal);
            totalTax = totalTax.plus(lineTax);
            grandTotal = grandTotal.plus(lineTotal);
        }

        if (sumAfterPromoBeforeBill.amount().signum() > 0 && !totalManualDiscount.isZero()) {
            BigDecimal overallManualPercent =
                    totalManualDiscount
                            .amount()
                            .multiply(ONE_HUNDRED)
                            .divide(sumAfterPromoBeforeBill.amount(), 4, RoundingMode.HALF_UP)
                            .setScale(2, RoundingMode.HALF_UP);
            if (overallManualPercent.compareTo(maxEffectiveManualPercent) > 0) {
                maxEffectiveManualPercent = overallManualPercent;
            }
        }

        return new SaleTotals(
                List.copyOf(computedLines),
                totalSubtotal,
                totalPromotionDiscount,
                totalManualDiscount,
                billDiscount,
                totalTax,
                grandTotal,
                maxEffectiveManualPercent,
                anyBelowCost);
    }
}
