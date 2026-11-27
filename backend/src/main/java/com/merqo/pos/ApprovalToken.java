package com.merqo.pos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.pos.domain.ApprovalTokenRules;

/**
 * Single-use, short-lived manager discount approval token.
 *
 * <p>Responsibility: persist only the SHA-256 hash of a random 128-bit approval token together with
 * its cashier, branch, ceiling percentage, approver, expiry, and consumption timestamp.
 *
 * <p>OOP concept: <b>Encapsulation &amp; Value Adapter</b> — hides storage details and adapts the
 * entity into an immutable {@link ApprovalTokenRules.TokenSnapshot} for pure domain validation.
 */
@Entity
@Table(name = "approval_tokens")
public class ApprovalToken {

    @Id private UUID id;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "max_discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDiscountPercent;

    @Column(name = "approver_id", nullable = false)
    private UUID approverId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    /** Required by JPA. */
    protected ApprovalToken() {}

    public ApprovalToken(
            UUID id,
            String tokenHash,
            UUID cashierId,
            UUID locationId,
            BigDecimal maxDiscountPercent,
            UUID approverId,
            Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.cashierId = cashierId;
        this.locationId = locationId;
        this.maxDiscountPercent = maxDiscountPercent;
        this.approverId = approverId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public ApprovalTokenRules.TokenSnapshot toSnapshot() {
        return new ApprovalTokenRules.TokenSnapshot(
                cashierId, locationId, maxDiscountPercent, expiresAt, usedAt);
    }

    public void markUsed(Instant usedAt) {
        this.usedAt = usedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getCashierId() {
        return cashierId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public BigDecimal getMaxDiscountPercent() {
        return maxDiscountPercent;
    }

    public UUID getApproverId() {
        return approverId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
