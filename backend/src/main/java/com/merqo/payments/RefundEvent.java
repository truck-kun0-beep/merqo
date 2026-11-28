package com.merqo.payments;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.merqo.payments.domain.RefundPayoutMethod;
import com.merqo.payments.domain.RefundStatus.RefundEventType;

/** Append-only transition event recorded against a {@link RefundRequest}. */
@Entity
@Table(name = "refund_events")
public class RefundEvent {

    @Id private UUID id;

    @Column(name = "refund_id", nullable = false)
    private UUID refundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private RefundEventType eventType;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "note")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_method", length = 20)
    private RefundPayoutMethod payoutMethod;

    @Column(name = "payout_reference", length = 64)
    private String payoutReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefundEvent() {}

    public RefundEvent(
            UUID id,
            UUID refundId,
            RefundEventType eventType,
            UUID actorId,
            String note,
            RefundPayoutMethod payoutMethod,
            String payoutReference,
            Instant createdAt) {
        this.id = id;
        this.refundId = refundId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.note = note;
        this.payoutMethod = payoutMethod;
        this.payoutReference = payoutReference;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public RefundEventType getEventType() {
        return eventType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getNote() {
        return note;
    }

    public RefundPayoutMethod getPayoutMethod() {
        return payoutMethod;
    }

    public String getPayoutReference() {
        return payoutReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
