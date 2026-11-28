package com.merqo.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.payments.domain.OnlinePaymentMethod;

/** Mutable simulator state representing a provider transaction in {@code PAYMENT_MODE=mock}. */
@Entity
@Table(name = "mock_provider_transactions")
public class MockProviderTransaction {

    @Id private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private OnlinePaymentMethod method;

    @Column(name = "reference", nullable = false, length = 64)
    private String reference;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "sender_hint", length = 80)
    private String senderHint;

    @Column(name = "claimed_by_payment_id")
    private UUID claimedByPaymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MockProviderTransaction() {}

    public MockProviderTransaction(
            UUID id,
            OnlinePaymentMethod method,
            String reference,
            BigDecimal amount,
            String senderHint,
            UUID claimedByPaymentId,
            Instant createdAt) {
        this.id = id;
        this.method = method;
        this.reference = reference;
        this.amount = amount;
        this.senderHint = senderHint;
        this.claimedByPaymentId = claimedByPaymentId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public OnlinePaymentMethod getMethod() {
        return method;
    }

    public String getReference() {
        return reference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getSenderHint() {
        return senderHint;
    }

    public UUID getClaimedByPaymentId() {
        return claimedByPaymentId;
    }

    public void setClaimedByPaymentId(UUID claimedByPaymentId) {
        this.claimedByPaymentId = claimedByPaymentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
