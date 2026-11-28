package com.merqo.integration;

import java.util.UUID;

/** Computes derived soft-hold quantities for a variant at the fulfillment warehouse. */
@FunctionalInterface
public interface SoftHoldReader {

    /**
     * Sum of quantities across unconfirmed online/manual orders ({@code PENDING_CONFIRMATION} and
     * {@code FOLLOW_UP}) at {@code warehouseId} for {@code variantId}.
     */
    int softHoldForVariant(UUID warehouseId, UUID variantId);
}
