package com.merqo.pos.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Validation rules for single-use manager discount approval tokens.
 *
 * <p>Responsibility: verify that an approval token exists, has not been used, has not expired
 * (5-minute TTL measured via an injected {@link Clock}), matches the submitting cashier and sale
 * location, and covers the requested discount percentage.
 *
 * <p>OOP concept: <b>Dependency Injection of Time &amp; Value Object</b> — takes {@link Clock}
 * explicitly and evaluates an immutable {@link TokenSnapshot} without database access.
 */
public final class ApprovalTokenRules {

    /** Lifetime of an issued single-use approval token. */
    public static final Duration TOKEN_TTL = Duration.ofMinutes(5);

    private ApprovalTokenRules() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Immutable view of an approval token for rule validation.
     *
     * @param cashierId cashier the token was issued for
     * @param locationId location the token is scoped to
     * @param maxDiscountPercent approved ceiling percentage
     * @param expiresAt UTC expiry instant
     * @param usedAt UTC instant when consumed, or {@code null} if still unused
     */
    public record TokenSnapshot(
            UUID cashierId,
            UUID locationId,
            BigDecimal maxDiscountPercent,
            Instant expiresAt,
            Instant usedAt) {}

    /**
     * Checks whether a token is valid for the given cashier, location, and requested discount.
     *
     * @param token token snapshot (nullable)
     * @param cashierId submitting cashier id
     * @param locationId sale location id
     * @param requestedDiscountPercent effective discount percentage requested
     * @param clock clock used to check expiry
     * @return {@code true} if valid
     */
    public static boolean isValid(
            TokenSnapshot token,
            UUID cashierId,
            UUID locationId,
            BigDecimal requestedDiscountPercent,
            Clock clock) {
        if (token == null || cashierId == null || locationId == null || clock == null) {
            return false;
        }
        if (token.usedAt() != null) {
            return false;
        }
        Instant now = clock.instant();
        if (token.expiresAt() == null || !now.isBefore(token.expiresAt())) {
            return false;
        }
        if (!cashierId.equals(token.cashierId()) || !locationId.equals(token.locationId())) {
            return false;
        }
        if (requestedDiscountPercent == null || token.maxDiscountPercent() == null) {
            return false;
        }
        return requestedDiscountPercent.compareTo(token.maxDiscountPercent()) <= 0;
    }

    /**
     * Validates a token and throws {@link PosRuleException} with {@code APPROVAL_INVALID} if any
     * rule fails.
     *
     * @param token token snapshot
     * @param cashierId submitting cashier id
     * @param locationId sale location id
     * @param requestedDiscountPercent effective discount percentage requested
     * @param clock clock used to check expiry
     */
    public static void validateOrThrow(
            TokenSnapshot token,
            UUID cashierId,
            UUID locationId,
            BigDecimal requestedDiscountPercent,
            Clock clock) {
        if (!isValid(token, cashierId, locationId, requestedDiscountPercent, clock)) {
            throw new PosRuleException(
                    "APPROVAL_INVALID",
                    422,
                    "Manager approval token is invalid, expired, already used, or insufficient.");
        }
    }
}
