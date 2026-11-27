package com.merqo.pos.domain;

import java.util.Locale;

/**
 * The four payment methods accepted at the Merqo POS counter.
 *
 * <p>Responsibility: identify each payment method and encapsulate whether it requires a reference,
 * what kind of reference it accepts, whether that reference is globally unique, and whether it
 * affects the physical cash drawer.
 *
 * <p>OOP concept: <b>Enum with behaviour (Polymorphism / Encapsulation)</b> — each constant carries
 * its own payment properties, avoiding switch statements scattered across services.
 */
public enum PaymentMethod {
    /** Physical cash collected into the cash drawer; supports tendered amount and change. */
    CASH(false, ReferenceKind.NONE, true, false),

    /** bKash mobile financial service transfer; requires a globally unique TrxID. */
    BKASH(true, ReferenceKind.TRX_ID, false, true),

    /** Nagad mobile financial service transfer; requires a globally unique TrxID. */
    NAGAD(true, ReferenceKind.TRX_ID, false, true),

    /** Card payment; requires an approval code or last 4 digits (never a full card number). */
    CARD(true, ReferenceKind.CARD_APPROVAL_OR_LAST4, false, false);

    /** The category of external reference a payment method expects. */
    public enum ReferenceKind {
        NONE,
        TRX_ID,
        CARD_APPROVAL_OR_LAST4
    }

    private final boolean requiresReference;
    private final ReferenceKind referenceKind;
    private final boolean affectsCashDrawer;
    private final boolean globallyUniqueReference;

    PaymentMethod(
            boolean requiresReference,
            ReferenceKind referenceKind,
            boolean affectsCashDrawer,
            boolean globallyUniqueReference) {
        this.requiresReference = requiresReference;
        this.referenceKind = referenceKind;
        this.affectsCashDrawer = affectsCashDrawer;
        this.globallyUniqueReference = globallyUniqueReference;
    }

    /**
     * Whether a payment reference is mandatory for this method.
     *
     * @return {@code true} when a reference must be provided
     */
    public boolean requiresReference() {
        return requiresReference;
    }

    /**
     * The kind of reference this method expects.
     *
     * @return the reference kind
     */
    public ReferenceKind getReferenceKind() {
        return referenceKind;
    }

    /**
     * Whether payments and refunds using this method change the cash drawer balance.
     *
     * @return {@code true} for cash
     */
    public boolean affectsCashDrawer() {
        return affectsCashDrawer;
    }

    /**
     * Whether the normalized reference must be globally unique across all locations.
     *
     * @return {@code true} for bKash and Nagad TrxIDs
     */
    public boolean isGloballyUniqueReference() {
        return globallyUniqueReference;
    }

    /**
     * Parses a payment method code case-insensitively.
     *
     * @param raw the raw method string
     * @return the matching {@link PaymentMethod}
     */
    public static PaymentMethod fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PosRuleException("VALIDATION_FAILED", 422, "Payment method is required.");
        }
        try {
            return PaymentMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PosRuleException(
                    "VALIDATION_FAILED", 422, "Unsupported payment method: " + raw);
        }
    }
}
