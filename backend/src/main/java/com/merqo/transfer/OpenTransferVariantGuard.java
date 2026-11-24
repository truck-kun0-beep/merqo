package com.merqo.transfer;

import java.util.List;

import org.springframework.stereotype.Component;

import com.merqo.catalog.ProductVariant;
import com.merqo.catalog.VariantDeactivationGuard;

/**
 * The veto of the transfer module on deactivating a variant.
 *
 * <p>Responsibility: say that a variant may not be deactivated while it is on an open transfer.
 *
 * <p>A variant that is on an open transfer keeps its barcode and stays sellable, because the goods
 * are still moving. The rule is registered as a Spring bean, so the catalog service finds it
 * without knowing that the transfer module exists.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Strategy</b> - the guard is one small object with one answer.
 *   <li><b>Observer</b> - Spring discovers the guard, so a later module adds its own veto without
 *       changing the catalog service.
 *   <li><b>Interface implementation</b> - the class implements the contract of the catalog module.
 *   <li><b>Open/closed principle</b> - a new module adds a guard instead of changing the catalog
 *       service.
 * </ul>
 */
@Component
public class OpenTransferVariantGuard implements VariantDeactivationGuard {

    private final StockTransferRepository transferRepository;

    /**
     * Creates the guard.
     *
     * @param transferRepository the transfers
     */
    public OpenTransferVariantGuard(StockTransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @Override
    public GuardResult check(ProductVariant variant) {
        List<StockTransfer> open = transferRepository.findOpenTransfersOfVariant(variant.getId());
        if (open.isEmpty()) {
            return GuardResult.allow();
        }
        return GuardResult.refuse(
                "The variant is on the open transfer "
                        + open.get(0).getNumber()
                        + ", so it may not be deactivated yet.");
    }
}
