package com.merqo.pos.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.merqo.catalog.domain.LinePricing;
import com.merqo.common.money.Money;

/**
 * Immutable result of calculating a full POS sale.
 *
 * <p>Responsibility: hold every computed line breakdown and bill-level total produced by {@link
 * SaleCalculator}.
 *
 * <p>OOP concept: <b>Value Object &amp; Composition</b> — groups line-level {@link LinePricing}
 * components and bill-level monetary totals into immutable records.
 *
 * @param lines per-line computed breakdown
 * @param subtotal sum of net line subtotals before tax (after all discounts)
 * @param promotionDiscount total promotion discount across all lines
 * @param manualDiscount total manual discount (line manual + whole-bill discount)
 * @param billDiscount total whole-bill discount applied
 * @param tax total tax across all lines
 * @param total final payable amount
 * @param maxEffectiveManualDiscountPercent highest manual discount percent across lines or bill
 * @param anyBelowCost whether any line sells below unit cost
 */
public record SaleTotals(
        List<ComputedLine> lines,
        Money subtotal,
        Money promotionDiscount,
        Money manualDiscount,
        Money billDiscount,
        Money tax,
        Money total,
        BigDecimal maxEffectiveManualDiscountPercent,
        boolean anyBelowCost) {

    /**
     * Computed pricing for a single sale line.
     *
     * @param variantId the product variant id
     * @param pricing base {@link LinePricing} from {@link com.merqo.catalog.domain.PriceCalculator}
     * @param lineManualDiscount manual discount directly on this line (line total)
     * @param billDiscountAllocated share of whole-bill discount allocated to this line
     * @param totalManualDiscount sum of {@code lineManualDiscount} and {@code
     *     billDiscountAllocated}
     * @param netUnitPrice effective unit price after all discounts
     * @param lineSubtotal net line subtotal after all discounts
     * @param tax computed tax for this line
     * @param lineTotal final line total including tax if exclusive (or equal to subtotal if
     *     inclusive)
     * @param taxRate effective tax rate applied
     * @param taxInclusive whether the list price is tax-inclusive
     * @param belowCost whether {@code netUnitPrice} is below unit cost
     * @param effectiveManualPercent manual discount percent on this line after promotion
     */
    public record ComputedLine(
            UUID variantId,
            LinePricing pricing,
            Money lineManualDiscount,
            Money billDiscountAllocated,
            Money totalManualDiscount,
            Money netUnitPrice,
            Money lineSubtotal,
            Money tax,
            Money lineTotal,
            BigDecimal taxRate,
            boolean taxInclusive,
            boolean belowCost,
            BigDecimal effectiveManualPercent) {}
}
