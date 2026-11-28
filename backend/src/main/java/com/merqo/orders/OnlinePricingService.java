package com.merqo.orders;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.domain.DiscountType;
import com.merqo.catalog.domain.PriceCalculator;
import com.merqo.catalog.domain.Scope;
import com.merqo.common.money.Money;
import com.merqo.promotion.Promotion;
import com.merqo.promotion.PromotionService;

/** Computes server-authoritative {@code ONLINE} unit prices including active ONLINE promotions. */
@Service
public class OnlinePricingService {

    private final PromotionService promotionService;

    public OnlinePricingService(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @Transactional(readOnly = true)
    public Money effectiveUnitPrice(ProductVariant variant, UUID warehouseId) {
        Money listPrice = Money.of(variant.getSellingPrice());
        Promotion promo =
                promotionService.resolve(variant.getId(), Scope.ONLINE, warehouseId).orElse(null);
        if (promo == null) {
            return listPrice;
        }
        Money discount =
                promo.getDiscountType() == DiscountType.PERCENT
                        ? PriceCalculator.percentDiscount(listPrice, promo.getValue())
                        : PriceCalculator.fixedDiscount(listPrice, Money.of(promo.getValue()));
        return listPrice.minus(discount);
    }
}
