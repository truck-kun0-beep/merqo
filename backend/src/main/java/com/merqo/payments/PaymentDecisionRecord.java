package com.merqo.payments;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.payments.domain.PaymentDecision;

/** Append-only immutable verification decision written once per {@link OrderPayment}. */
@Entity
@Table(name = "payment_decisions")
public class PaymentDecisionRecord {

    @Id private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 20)
    private PaymentDecision decision;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decider_kind", nullable = false, length = 40)
    private String deciderKind;

    @Column(name = "evidence")
    private String evidence;

    @Column(name = "provider_reference", length = 120)
    private String providerReference;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected PaymentDecisionRecord() {}

    public PaymentDecisionRecord(
            UUID id,
            UUID paymentId,
            PaymentDecision decision,
            UUID decidedBy,
            String deciderKind,
            String evidence,
            String providerReference,
            Instant decidedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.decision = decision;
        this.decidedBy = decidedBy;
        this.deciderKind = deciderKind;
        this.evidence = evidence;
        this.providerReference = providerReference;
        this.decidedAt = decidedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentDecision getDecision() {
        return decision;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public String getDeciderKind() {
        return deciderKind;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
